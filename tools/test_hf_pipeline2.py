import json
import sys
import numpy as np
import wave
import os

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import onnxruntime as ort
from transformers import WhisperTokenizer, WhisperFeatureExtractor

tokenizer = WhisperTokenizer.from_pretrained('openai/whisper-tiny')
feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')

with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

print(f'Audio: {len(pcmf)/sr:.2f}s')

result = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
input_features = result.input_features[0].astype(np.float32)

enc = ort.InferenceSession('onnx_models/tiny/onnx/encoder_model.onnx')
encoder_hidden = enc.run(None, {enc.get_inputs()[0].name: input_features.reshape(1, 80, 3000)})[0]

dec = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = {inp.name: inp for inp in dec.get_inputs()}
dec_output_names = [out.name for out in dec.get_outputs()]

sot = tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
lang_en = tokenizer.convert_tokens_to_ids('<|en|>')
task_transcribe = tokenizer.convert_tokens_to_ids('<|transcribe|>')
notimestamps = tokenizer.convert_tokens_to_ids('<|notimestamps|>')
prompt = [sot, lang_en, task_transcribe, notimestamps]
print(f'Prompt: {prompt}')

def run_decoder_step(input_ids, encoder_hidden, past_kv_dict, use_cache):
    feed = {}
    feed['input_ids'] = input_ids
    feed['encoder_hidden_states'] = encoder_hidden.astype(np.float32)
    feed['use_cache_branch'] = np.array([use_cache], dtype=np.bool_)
    
    for inp_name, inp_obj in dec_inputs.items():
        if inp_name in ('input_ids', 'encoder_hidden_states', 'use_cache_branch'):
            continue
        if inp_name in past_kv_dict:
            feed[inp_name] = past_kv_dict[inp_name]
        else:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    
    results = dec.run(None, feed)
    logits = results[0]
    new_kv = {}
    for i, name in enumerate(dec_output_names):
        if name.startswith('present.'):
            past_name = name.replace('present.', 'past_key_values.')
            new_kv[past_name] = results[i]
    return logits, new_kv

print('\n--- Greedy decode (50 steps) ---')
tokens = list(prompt)
past_kv = {}

for step in range(50):
    if step == 0:
        ids = np.array([tokens], dtype=np.int64)
        use_cache = False
    else:
        ids = np.array([[tokens[-1]]], dtype=np.int64)
        use_cache = True
    
    logits, new_kv = run_decoder_step(ids, encoder_hidden, past_kv, use_cache)
    past_kv = new_kv
    
    next_logits = logits[0, -1, :]
    next_token = int(np.argmax(next_logits))
    tokens.append(next_token)
    
    # Decode what the model is saying
    text = tokenizer.decode([next_token])
    print(f'  step {step}: id={next_token} text={repr(text)} logit={next_logits[next_token]:.2f}')
    
    if next_token == tokenizer.eos_token_id:
        print('  -> EOT!')
        break

print(f'\nAll tokens after prompt: {tokens[len(prompt):]}')
text = tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
print(f'Transcription: {repr(text)}')
