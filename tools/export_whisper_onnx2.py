import sys
import os
import numpy as np

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
from transformers import WhisperForConditionalGeneration

print('Loading whisper-tiny...')
model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()

encoder = model.get_encoder()
decoder = model.get_decoder()
proj_out = model.proj_out

output_dir = 'onnx_models/whisper_tiny_fixed'
os.makedirs(output_dir, exist_ok=True)

# === Export Encoder ===
print('\n=== Exporting Encoder ===')
enc_dummy = torch.randn(1, 80, 3000)
torch.onnx.export(
    encoder,
    (enc_dummy,),
    os.path.join(output_dir, 'encoder_model.onnx'),
    input_names=['input_features'],
    output_names=['last_hidden_state'],
    dynamic_axes={'input_features': {0: 'batch', 2: 'seq_len'}, 'last_hidden_state': {0: 'batch', 1: 'enc_seq'}},
    opset_version=17,
)
print('Encoder exported!')

# === Export Decoder (with KV cache) ===
print('\n=== Exporting Decoder ===')

# For merged decoder, we need to trace through the decoder with and without cache
# The decoder inputs are:
#   input_ids: [batch, seq_len]
#   encoder_hidden_states: [batch, enc_seq, 384]
#   past_key_values.N.decoder.key: [batch, 6, kv_seq, 64]
#   past_key_values.N.decoder.value: [batch, 6, kv_seq, 64]
#   past_key_values.N.encoder.key: [batch, 6, enc_seq, 64]
#   past_key_values.N.encoder.value: [batch, 6, enc_seq, 64]
#   use_cache_branch: bool

# We'll create a wrapper that returns logits + present key values
class DecoderWrapper(torch.nn.Module):
    def __init__(self, decoder, proj_out):
        super().__init__()
        self.decoder = decoder
        self.proj_out = proj_out
    
    def forward(
        self,
        input_ids,
        encoder_hidden_states,
        past_key_values_0_decoder_key,
        past_key_values_0_decoder_value,
        past_key_values_0_encoder_key,
        past_key_values_0_encoder_value,
        past_key_values_1_decoder_key,
        past_key_values_1_decoder_value,
        past_key_values_1_encoder_key,
        past_key_values_1_encoder_value,
        past_key_values_2_decoder_key,
        past_key_values_2_decoder_value,
        past_key_values_2_encoder_key,
        past_key_values_2_encoder_value,
        past_key_values_3_decoder_key,
        past_key_values_3_decoder_value,
        past_key_values_3_encoder_key,
        past_key_values_3_encoder_value,
        use_cache_branch,
    ):
        batch_size = input_ids.shape[0]
        past_key_values = []
        for i in range(4):
            layer_past = (
                (past_key_values_0_decoder_key, past_key_values_0_decoder_value, past_key_values_0_encoder_key, past_key_values_0_encoder_value) if i == 0 else
                (past_key_values_1_decoder_key, past_key_values_1_decoder_value, past_key_values_1_encoder_key, past_key_values_1_encoder_value) if i == 1 else
                (past_key_values_2_decoder_key, past_key_values_2_decoder_value, past_key_values_2_encoder_key, past_key_values_2_encoder_value) if i == 2 else
                (past_key_values_3_decoder_key, past_key_values_3_decoder_value, past_key_values_3_encoder_key, past_key_values_3_encoder_value)
            )
            past_key_values.append(layer_past)
        
        use_cache = bool(use_cache_branch.item())
        
        outputs = self.decoder(
            input_ids=input_ids,
            encoder_hidden_states=encoder_hidden_states,
            past_key_values=past_key_values,
            use_cache=use_cache,
            return_dict=True,
        )
        
        logits = self.proj_out(outputs.logits)
        
        present_key_values = []
        if use_cache and outputs.past_key_values is not None:
            for layer_kv in outputs.past_key_values:
                present_key_values.extend(list(layer_kv))
        else:
            for _ in range(4):
                present_key_values.extend([
                    torch.zeros(1, 6, 0, 64),
                    torch.zeros(1, 6, 0, 64),
                    torch.zeros(1, 6, 0, 64),
                    torch.zeros(1, 6, 0, 64),
                ])
        
        return (logits, *present_key_values)

wrapper = DecoderWrapper(decoder, proj_out)

input_names = ['input_ids', 'encoder_hidden_states', 'use_cache_branch']
output_names = ['logits']

kv_input_names = []
for i in range(4):
    for kv in ['decoder.key', 'decoder.value', 'encoder.key', 'encoder.value']:
        name = f'past_key_values.{i}.{kv}'
        input_names.append(name)
        kv_input_names.append(name)

for i in range(4):
    for kv in ['decoder.key', 'decoder.value', 'encoder.key', 'encoder.value']:
        name = f'present.{i}.{kv}'
        output_names.append(name)

# Dummy inputs for step 0 (no cache)
dummy_input_ids = torch.tensor([[50258, 50259, 50359, 50363]], dtype=torch.long)
dummy_enc = torch.randn(1, 1500, 384)
dummy_cache = torch.zeros(1, 6, 0, 64)
dummy_bool = torch.tensor([False])

dummy_inputs = [
    dummy_input_ids,
    dummy_enc,
]
for _ in range(16):
    dummy_inputs.append(dummy_cache)
dummy_inputs.append(dummy_bool)

dynamic_axes = {
    'input_ids': {0: 'batch', 1: 'seq_len'},
    'encoder_hidden_states': {0: 'batch', 1: 'enc_seq'},
    'logits': {0: 'batch', 1: 'dec_seq'},
}
for name in input_names[2:]:  # KV caches and use_cache
    dynamic_axes[name] = {0: 'batch', 2: 'kv_len'}
for name in output_names[1:]:  # present KV
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
    )
print('Decoder exported!')

# List files
for f in os.listdir(output_dir):
    size = os.path.getsize(os.path.join(output_dir, f))
    print(f'  {f}: {size/1024/1024:.1f} MB')
