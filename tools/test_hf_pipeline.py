import json
import sys
import numpy as np
import wave
import os

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import onnxruntime as ort

# Load the FULL tokenizer (includes special tokens)
from transformers import WhisperTokenizer
tokenizer = WhisperTokenizer.from_pretrained('openai/whisper-tiny')

# Load feature extractor  
from transformers import WhisperFeatureExtractor
feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')

# Load audio
with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

print(f'Audio: {len(pcmf)/sr:.2f}s, sr={sr}')

# Step 1: Get the CORRECT input features from HuggingFace feature extractor
result = feat_extractor(
    pcmf.tolist(),
    sampling_rate=16000,
    return_tensors='np',
    return_attention_mask=False,
)
input_features = result.input_features[0]  # [80, 3000]
print(f'HF input_features: shape={input_features.shape}, range=[{input_features.min():.4f}, {input_features.max():.4f}]')

# Step 2: Run through ONNX encoder
enc = ort.InferenceSession('onnx_models/tiny/onnx/encoder_model.onnx')
enc_input = enc.get_inputs()[0]
encoder_hidden = enc.run(None, {enc_input.name: input_features.reshape(1, 80, 3000).astype(np.float32)})[0]
print(f'Encoder output: shape={encoder_hidden.shape}, mean={encoder_hidden.mean():.6f}, std={encoder_hidden.std():.6f}')

# Step 3: Get decoder prompt token IDs
prompt_ids = tokenizer.encode('', add_special_tokens=False)
sot = tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
lang_en = tokenizer.convert_tokens_to_ids('<|en|>')
task_transcribe = tokenizer.convert_tokens_to_ids('<|transcribe|>')
notimestamps = tokenizer.convert_tokens_to_ids('<|notimestamps|>')

decoder_prompt = [sot, lang_en, task_transcribe, notimestamps]
print(f'Decoder prompt: {decoder_prompt}')
print(f'  SOT={sot}, EN={lang_en}, TRANSCRIBE={task_transcribe}, NOTIMESTAMPS={notimestamps}')

# Step 4: Greedy decode with ONNX decoder
dec = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = dec.get_inputs()
dec_outputs = dec.get_outputs()

print(f'\nDecoder inputs: {[inp.name for inp in dec_inputs]}')
print(f'Decoder outputs: {[out.name for out in dec_outputs]}')

# Check decoder input shapes
for inp in dec_inputs:
    print(f'  {inp.name}: type={inp.type}')

tokens = list(decoder_prompt)
present_kv = None

print(f'\n--- Greedy decode (30 steps) ---')
for step in range(30):
    if step == 0:
        input_ids = np.array([tokens], dtype=np.int64)
        use_cache = np.array([False], dtype=np.bool_)
    else:
        input_ids = np.array([[tokens[-1]]], dtype=np.int64)
        use_cache = np.array([True], dtype=np.bool_)
    
    feed = {}
    for inp in dec_inputs:
        n = inp.name
        if n == 'input_ids':
            feed[n] = input_ids
        elif 'encoder_hidden_states' in n:
            feed[n] = encoder_hidden.astype(np.float32)
        elif 'past_key_values' in n:
            if present_kv is not None:
                # Find matching present output
                past_key = n.replace('past_key_values', 'present')
                for i, out in enumerate(dec_outputs):
                    if out.name == past_key:
                        feed[n] = present_kv[i]
                        break
                else:
                    # Empty cache
                    shape = [1, 6, 0, 64] if 'key' in n else [1, 6, 0, 64]
                    feed[n] = np.zeros(shape, dtype=np.float32)
            else:
                shape = [1, 6, 0, 64] if 'key' in n else [1, 6, 0, 64]
                feed[n] = np.zeros(shape, dtype=np.float32)
        elif n == 'use_cache_branch':
            feed[n] = use_cache
    
    result = dec.run(None, feed)
    logits = result[0]
    present_kv = result[1:]
    
    # Get next token from last position
    next_logits = logits[0, -1, :]
    next_token = int(np.argmax(next_logits))
    tokens.append(next_token)
    
    # Show top 5
    top5 = np.argsort(next_logits)[-5:][::-1]
    top5_str = ', '.join([f'{tokenizer.decode([t])}({next_logits[t]:.2f})' for t in top5])
    print(f'  step {step}: token={next_token} ({tokenizer.decode([next_token])}) | top5: {top5_str}')
    
    # Stop at EOT
    if next_token == tokenizer.eos_token_id:
        print(f'  -> EOT reached!')
        break

# Decode the full output
transcription = tokenizer.decode(tokens, skip_special_tokens=True)
print(f'\nFull tokens: {tokens}')
print(f'Transcription: {repr(transcription)}')

# Also try with initial_prompt matching what Android sends
print('\n\n=== Test 2: With initial_prompt (matching Android behavior) ===')
tokens2 = list(decoder_prompt)
present_kv2 = None

for step in range(30):
    if step == 0:
        input_ids = np.array([tokens2], dtype=np.int64)
        use_cache = np.array([False], dtype=np.bool_)
    else:
        input_ids = np.array([[tokens2[-1]]], dtype=np.int64)
        use_cache = np.array([True], dtype=np.bool_)
    
    feed = {}
    for inp in dec_inputs:
        n = inp.name
        if n == 'input_ids':
            feed[n] = input_ids
        elif 'encoder_hidden_states' in n:
            feed[n] = encoder_hidden.astype(np.float32)
        elif 'past_key_values' in n:
            if present_kv2 is not None:
                past_key = n.replace('past_key_values', 'present')
                for i, out in enumerate(dec_outputs):
                    if out.name == past_key:
                        feed[n] = present_kv2[i]
                        break
                else:
                    feed[n] = np.zeros([1, 6, 0, 64], dtype=np.float32)
            else:
                feed[n] = np.zeros([1, 6, 0, 64], dtype=np.float32)
        elif n == 'use_cache_branch':
            feed[n] = use_cache
    
    result = dec.run(None, feed)
    logits = result[0]
    present_kv2 = result[1:]
    
    next_logits = logits[0, -1, :]
    next_token = int(np.argmax(next_logits))
    tokens2.append(next_token)
    
    if next_token == tokenizer.eos_token_id:
        print(f'  step {step}: EOT reached')
        break

transcription2 = tokenizer.decode(tokens2, skip_special_tokens=True)
print(f'Transcription: {repr(transcription2)}')
