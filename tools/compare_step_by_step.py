import sys, os, numpy as np, wave
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
import onnxruntime as ort
from transformers import WhisperForConditionalGeneration, WhisperProcessor

model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()
processor = WhisperProcessor.from_pretrained('openai/whisper-tiny')

with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

inputs = processor(pcmf, sampling_rate=16000, return_tensors='pt')
input_features = inputs.input_features.numpy().astype(np.float32)

# Load ONNX encoder
enc_onnx = ort.InferenceSession('onnx_models/tiny/onnx/encoder_model.onnx')
enc_out_onnx = enc_onnx.run(None, {enc_onnx.get_inputs()[0].name: input_features})[0]

# Load ONNX decoder
dec_onnx = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = {inp.name: inp for inp in dec_onnx.get_inputs()}
dec_output_names = [out.name for out in dec_onnx.get_outputs()]

# PyTorch encoder
with torch.no_grad():
    enc_out_pt = model.get_encoder()(torch.from_numpy(input_features)).last_hidden_state.numpy()

prompt = [50258, 50259, 50359, 50363]
suppress_from = 50257

# Step-by-step comparison
pt_tokens = list(prompt)
onnx_tokens = list(prompt)
pt_past = None
onnx_past = {}

for step in range(10):
    # === PyTorch ===
    if step == 0:
        pt_ids = torch.tensor([pt_tokens], dtype=torch.long)
        use_cache = False
    else:
        pt_ids = torch.tensor([[pt_tokens[-1]]], dtype=torch.long)
        use_cache = True
    
    with torch.no_grad():
        pt_out = model.get_decoder()(
            input_ids=pt_ids,
            encoder_hidden_states=torch.from_numpy(enc_out_pt),
            use_cache=use_cache,
            past_key_values=pt_past,
            return_dict=True,
        )
        pt_past = pt_out.past_key_values
        pt_logits = model.proj_out(pt_out.last_hidden_state).numpy()
    
    pt_next_logits = pt_logits[0, -1, :]
    pt_next_logits[suppress_from:] = -1e9
    pt_next_token = int(np.argmax(pt_next_logits))
    pt_tokens.append(pt_next_token)
    
    # === ONNX ===
    if step == 0:
        onnx_ids = np.array([onnx_tokens], dtype=np.int64)
        onnx_use_cache = False
    else:
        onnx_ids = np.array([[onnx_tokens[-1]]], dtype=np.int64)
        onnx_use_cache = True
    
    feed = {}
    feed['input_ids'] = onnx_ids
    feed['encoder_hidden_states'] = enc_out_onnx.astype(np.float32)
    feed['use_cache_branch'] = np.array([onnx_use_cache], dtype=np.bool_)
    
    for inp_name in dec_inputs:
        if inp_name in feed:
            continue
        if inp_name in onnx_past:
            feed[inp_name] = onnx_past[inp_name]
        else:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    
    results = dec_onnx.run(None, feed)
    onnx_logits = results[0]
    onnx_past = {}
    for i, name in enumerate(dec_output_names):
        if name.startswith('present.'):
            onnx_past[name.replace('present.', 'past_key_values.')] = results[i].numpy() if hasattr(results[i], 'numpy') else results[i]
    
    onnx_next_logits = onnx_logits[0, -1, :]
    onnx_next_logits[suppress_from:] = -1e9
    onnx_next_token = int(np.argmax(onnx_next_logits))
    onnx_tokens.append(onnx_next_token)
    
    # Compare
    logit_diff = np.abs(pt_next_logits - onnx_next_logits)
    pt_text = processor.tokenizer.decode([pt_next_token])
    onnx_text = processor.tokenizer.decode([onnx_next_token])
    
    status = "OK" if pt_next_token == onnx_next_token else "MISMATCH"
    print(f'Step {step}: PT={pt_next_token}({repr(pt_text)}) ONNX={onnx_next_token}({repr(onnx_text)}) logit_diff=max:{logit_diff.max():.6f} mean:{logit_diff.mean():.6f} {status}')

print(f'\nPT result: {repr(processor.tokenizer.decode(pt_tokens, skip_special_tokens=True)[:200])}')
print(f'ONNX result: {repr(processor.tokenizer.decode(onnx_tokens, skip_special_tokens=True)[:200])}')
