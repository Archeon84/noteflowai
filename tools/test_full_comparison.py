import sys
import numpy as np
import wave
import os

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
from transformers import WhisperForConditionalGeneration, WhisperProcessor

processor = WhisperProcessor.from_pretrained('openai/whisper-tiny')
model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()

# Load audio
with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

print(f'Audio: {len(pcmf)/sr:.2f}s')

# Use processor to get correct input features
inputs = processor(pcmf, sampling_rate=16000, return_tensors='pt')
input_features = inputs.input_features
print(f'Input features: {input_features.shape}, range=[{input_features.min():.4f}, {input_features.max():.4f}]')

# Run through the actual PyTorch model
with torch.no_grad():
    # Generate using the model's generate method (gold standard)
    generated = model.generate(
        input_features,
        language='en',
        task='transcribe',
        max_length=448,
    )
    transcription = processor.batch_decode(generated, skip_special_tokens=True)[0]
    print(f'\nPyTorch model transcription: {repr(transcription)}')

    # Also run encoder manually to compare with ONNX
    encoder_outputs = model.get_encoder()(input_features)
    enc_hidden = encoder_outputs.last_hidden_state
    print(f'\nPyTorch encoder output: shape={enc_hidden.shape}, mean={enc_hidden.mean():.6f}, std={enc_hidden.std():.6f}')

# Now run ONNX encoder with same input features for comparison
import onnxruntime as ort
enc_onnx = ort.InferenceSession('onnx_models/tiny/onnx/encoder_model.onnx')
onnx_input = input_features.numpy().reshape(1, 80, 3000).astype(np.float32)
onnx_hidden = enc_onnx.run(None, {enc_onnx.get_inputs()[0].name: onnx_input})[0]
print(f'ONNX encoder output:   shape={onnx_hidden.shape}, mean={onnx_hidden.mean():.6f}, std={onnx_hidden.std():.6f}')

diff = np.abs(enc_hidden.numpy() - onnx_hidden)
print(f'PyTorch vs ONNX encoder diff: max={diff.max():.6f} mean={diff.mean():.6f}')

# Now run ONNX decoder with PyTorch encoder output vs ONNX encoder output
dec_onnx = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = {inp.name: inp for inp in dec_onnx.get_inputs()}
dec_output_names = [out.name for out in dec_onnx.get_outputs()]

from transformers import WhisperTokenizer
tokenizer = WhisperTokenizer.from_pretrained('openai/whisper-tiny')
sot = tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
lang_en = tokenizer.convert_tokens_to_ids('<|en|>')
task_transcribe = tokenizer.convert_tokens_to_ids('<|transcribe|>')
notimestamps = tokenizer.convert_tokens_to_ids('<|notimestamps|>')
prompt = [sot, lang_en, task_transcribe, notimestamps]

def decode_with_encoder(enc_out, label, steps=10):
    print(f'\n--- {label} (first 10 steps) ---')
    tokens = list(prompt)
    past_kv = {}
    
    for step in range(steps):
        if step == 0:
            ids = np.array([tokens], dtype=np.int64)
            use_cache = False
        else:
            ids = np.array([[tokens[-1]]], dtype=np.int64)
            use_cache = True
        
        feed = {}
        feed['input_ids'] = ids
        feed['encoder_hidden_states'] = enc_out.astype(np.float32)
        feed['use_cache_branch'] = np.array([use_cache], dtype=np.bool_)
        
        for inp_name in dec_inputs:
            if inp_name in feed:
                continue
            if inp_name in past_kv:
                feed[inp_name] = past_kv[inp_name]
            else:
                feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
        
        results = dec_onnx.run(None, feed)
        logits = results[0]
        past_kv = {}
        for i, name in enumerate(dec_output_names):
            if name.startswith('present.'):
                past_name = name.replace('present.', 'past_key_values.')
                past_kv[past_name] = results[i]
        
        next_logits = logits[0, -1, :]
        next_token = int(np.argmax(next_logits))
        tokens.append(next_token)
        print(f'  step {step}: id={next_token} text={repr(tokenizer.decode([next_token]))} logit={next_logits[next_token]:.2f}')
    
    return tokens

# Test with PyTorch encoder output
decode_with_encoder(enc_hidden.numpy(), 'PyTorch encoder → ONNX decoder')

# Test with ONNX encoder output  
decode_with_encoder(onnx_hidden, 'ONNX encoder → ONNX decoder')
