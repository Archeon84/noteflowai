import sys
import os
import numpy as np

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
import onnxruntime as ort
from transformers import WhisperForConditionalGeneration, WhisperTokenizer, WhisperFeatureExtractor
import wave

model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()
encoder = model.get_encoder()
decoder = model.get_decoder()
proj_out = model.proj_out

output_dir = 'onnx_models/whisper_tiny_fixed'

# Verify encoder matches
print('=== Verifying encoder ===')
enc_onnx = ort.InferenceSession(os.path.join(output_dir, 'encoder_model.onnx'))
dummy = torch.randn(1, 80, 3000)
with torch.no_grad():
    torch_out = encoder(dummy).last_hidden_state.numpy()
onnx_out = enc_onnx.run(None, {enc_onnx.get_inputs()[0].name: dummy.numpy()})[0]
diff = np.abs(torch_out - onnx_out)
print(f'Encoder diff: max={diff.max():.8f}, mean={diff.mean():.8f}')
print('Encoder OK!' if diff.max() < 1e-5 else 'Encoder MISMATCH!')

# Test with real audio
tokenizer = WhisperTokenizer.from_pretrained('openai/whisper-tiny')
feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')
with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

inputs = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
input_features = inputs.input_features[0].astype(np.float32)

# Get encoder outputs from both
with torch.no_grad():
    torch_enc = encoder(torch.from_numpy(input_features).unsqueeze(0)).last_hidden_state.numpy()
onnx_enc = enc_onnx.run(None, {enc_onnx.get_inputs()[0].name: input_features.reshape(1, 80, 3000)})[0]
diff = np.abs(torch_enc - onnx_enc)
print(f'Real audio encoder diff: max={diff.max():.8f}, mean={diff.mean():.8f}')

# === Export Decoder ===
print('\n=== Exporting Decoder ===')

class DecoderWrapper(torch.nn.Module):
    def __init__(self, decoder, proj_out, num_layers=4, num_heads=6, head_dim=64):
        super().__init__()
        self.decoder = decoder
        self.proj_out = proj_out
        self.num_layers = num_layers
        self.num_heads = num_heads
        self.head_dim = head_dim
    
    def forward(
        self,
        input_ids,
        encoder_hidden_states,
        past_key_values_0_decoder_key, past_key_values_0_decoder_value,
        past_key_values_0_encoder_key, past_key_values_0_encoder_value,
        past_key_values_1_decoder_key, past_key_values_1_decoder_value,
        past_key_values_1_encoder_key, past_key_values_1_encoder_value,
        past_key_values_2_decoder_key, past_key_values_2_decoder_value,
        past_key_values_2_encoder_key, past_key_values_2_encoder_value,
        past_key_values_3_decoder_key, past_key_values_3_decoder_value,
        past_key_values_3_encoder_key, past_key_values_3_encoder_value,
        use_cache_branch,
    ):
        past_key_values = []
        all_kv = [
            (past_key_values_0_decoder_key, past_key_values_0_decoder_value, past_key_values_0_encoder_key, past_key_values_0_encoder_value),
            (past_key_values_1_decoder_key, past_key_values_1_decoder_value, past_key_values_1_encoder_key, past_key_values_1_encoder_value),
            (past_key_values_2_decoder_key, past_key_values_2_decoder_value, past_key_values_2_encoder_key, past_key_values_2_encoder_value),
            (past_key_values_3_decoder_key, past_key_values_3_decoder_value, past_key_values_3_encoder_key, past_key_values_3_encoder_value),
        ]
        
        use_cache = bool(use_cache_branch.item())
        
        if use_cache:
            for dk, dv, ek, ev in all_kv:
                past_key_values.append((dk, dv, ek, ev))
        else:
            past_key_values = None
        
        outputs = self.decoder(
            input_ids=input_ids,
            encoder_hidden_states=encoder_hidden_states,
            past_key_values=past_key_values,
            use_cache=use_cache,
            return_dict=True,
        )
        
        logits = self.proj_out(outputs.logits)
        
        present_list = []
        if use_cache and outputs.past_key_values is not None:
            for layer_kv in outputs.past_key_values:
                present_list.extend(list(layer_kv))
        else:
            for _ in range(self.num_layers):
                present_list.extend([
                    torch.zeros(1, self.num_heads, 0, self.head_dim),
                    torch.zeros(1, self.num_heads, 0, self.head_dim),
                    torch.zeros(1, self.num_heads, 0, self.head_dim),
                    torch.zeros(1, self.num_heads, 0, self.head_dim),
                ])
        
        return (logits, *present_list)

wrapper = DecoderWrapper(decoder, proj_out)

input_names = ['input_ids', 'encoder_hidden_states', 'use_cache_branch']
output_names = ['logits']

for i in range(4):
    for kv in ['decoder.key', 'decoder.value', 'encoder.key', 'encoder.value']:
        input_names.append(f'past_key_values.{i}.{kv}')
for i in range(4):
    for kv in ['decoder.key', 'decoder.value', 'encoder.key', 'encoder.value']:
        output_names.append(f'present.{i}.{kv}')

# Dummy inputs for step 0
dummy_input_ids = torch.tensor([[50258, 50259, 50359, 50363]], dtype=torch.long)
dummy_enc = torch.randn(1, 1500, 384)
dummy_cache = torch.zeros(1, 6, 0, 64)
dummy_bool = torch.tensor([False])

dummy_inputs = [dummy_input_ids, dummy_enc]
for _ in range(16):
    dummy_inputs.append(dummy_cache)
dummy_inputs.append(dummy_bool)

dynamic_axes = {
    'input_ids': {0: 'batch', 1: 'seq_len'},
    'encoder_hidden_states': {0: 'batch', 1: 'enc_seq'},
    'logits': {0: 'batch', 1: 'dec_seq'},
}
for name in input_names[2:]:
    dynamic_axes[name] = {0: 'batch', 2: 'kv_len'}
for name in output_names[1:]:
    dynamic_axes[name] = {0: 'batch', 2: 'kv_len'}

print('Tracing decoder...')
with torch.no_grad():
    torch.onnx.export(
        wrapper,
        tuple(dummy_inputs),
        os.path.join(output_dir, 'decoder_model_merged.onnx'),
        input_names=input_names,
        output_names=output_names,
        dynamic_axes=dynamic_axes,
        opset_version=17,
        dynamo=False,
    )
print('Decoder exported!')

# List files
for f in os.listdir(output_dir):
    size = os.path.getsize(os.path.join(output_dir, f))
    print(f'  {f}: {size/1024/1024:.1f} MB')
