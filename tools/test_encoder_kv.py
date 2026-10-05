import sys, os, numpy as np, wave
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from transformers import WhisperFeatureExtractor, WhisperTokenizer
import onnxruntime as ort

feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')
tokenizer = WhisperTokenizer.from_pretrained('openai/whisper-tiny')

with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

result = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
hf_features = result.input_features[0].astype(np.float32)

enc = ort.InferenceSession('onnx_models/tiny/onnx/encoder_model.onnx')
onnx_enc = enc.run(None, {enc.get_inputs()[0].name: hf_features.reshape(1, 80, 3000)})[0]

dec = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = {inp.name: inp for inp in dec.get_inputs()}
dec_output_names = [out.name for out in dec.get_outputs()]

prompt = [50258, 50259, 50359, 50363]
suppress_from = 50257

# Step 0: get the full logits AND KV outputs
feed0 = {}
feed0['input_ids'] = np.array([prompt], dtype=np.int64)
feed0['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed0['use_cache_branch'] = np.array([False], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name not in feed0:
        feed0[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)

results0 = dec.run(None, feed0)
logits0 = results0[0]
next_logits = logits0[0, -1, :]
next_logits[suppress_from:] = -1e9
token0 = int(np.argmax(next_logits))
print(f'Step 0: token={token0} ({repr(tokenizer.decode([token0]))})')

# Get all present KV outputs
present_kv = {}
for i, name in enumerate(dec_output_names):
    if name.startswith('present.'):
        present_kv[name] = results0[i]

# Step 1 test A: with full cached KV (including encoder KV)
feed1a = {}
feed1a['input_ids'] = np.array([[token0]], dtype=np.int64)
feed1a['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed1a['use_cache_branch'] = np.array([True], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name in feed1a:
        continue
    past_name = inp_name.replace('past_key_values', 'present')
    if past_name in present_kv:
        feed1a[inp_name] = present_kv[past_name]
    else:
        feed1a[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)

results1a = dec.run(None, feed1a)
logits1a = results1a[0]
next_logits1a = logits1a[0, -1, :]
next_logits1a[suppress_from:] = -1e9
token1a = int(np.argmax(next_logits1a))
print(f'Step 1 (cached encoder KV): token={token1a} ({repr(tokenizer.decode([token1a]))})')
print(f'  top5: {[(int(t), repr(tokenizer.decode([t])), float(next_logits1a[t])) for t in np.argsort(next_logits1a)[-5:][::-1]]}')

# Step 1 test B: with decoder KV only, encoder KV = empty (forces recomputation)
feed1b = {}
feed1b['input_ids'] = np.array([[token0]], dtype=np.int64)
feed1b['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed1b['use_cache_branch'] = np.array([True], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name in feed1b:
        continue
    if 'encoder' in inp_name:
        feed1b[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    else:
        past_name = inp_name.replace('past_key_values', 'present')
        if past_name in present_kv:
            feed1b[inp_name] = present_kv[past_name]
        else:
            feed1b[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)

results1b = dec.run(None, feed1b)
logits1b = results1b[0]
next_logits1b = logits1b[0, -1, :]
next_logits1b[suppress_from:] = -1e9
token1b = int(np.argmax(next_logits1b))
print(f'Step 1 (empty encoder KV): token={token1b} ({repr(tokenizer.decode([token1b]))})')
print(f'  top5: {[(int(t), repr(tokenizer.decode([t])), float(next_logits1b[t])) for t in np.argsort(next_logits1b)[-5:][::-1]]}')

# Full decode with empty encoder KV (recompute every step)
print('\n=== Full decode: empty encoder KV at every step ===')
tokens = list(prompt)
past_kv_dec = {}

for step in range(30):
    if step == 0:
        ids = np.array([tokens], dtype=np.int64)
        use_cache = False
    else:
        ids = np.array([[tokens[-1]]], dtype=np.int64)
        use_cache = True
    
    feed = {}
    feed['input_ids'] = ids
    feed['encoder_hidden_states'] = onnx_enc.astype(np.float32)
    feed['use_cache_branch'] = np.array([use_cache], dtype=np.bool_)
    
    for inp_name in dec_inputs:
        if inp_name in feed:
            continue
        if 'encoder' in inp_name:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
        elif inp_name in past_kv_dec:
            feed[inp_name] = past_kv_dec[inp_name]
        else:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    
    results = dec.run(None, feed)
    logits = results[0]
    past_kv_dec = {}
    for i, name in enumerate(dec_output_names):
        if name.startswith('present.') and 'decoder' in name:
            past_kv_dec[name.replace('present.', 'past_key_values.')] = results[i]
    
    next_logits = logits[0, -1, :]
    next_logits[suppress_from:] = -1e9
    next_token = int(np.argmax(next_logits))
    tokens.append(next_token)
    
    text = tokenizer.decode([next_token])
    if step < 20 or next_token == tokenizer.eos_token_id:
        print(f'  step {step}: id={next_token} text={repr(text)}')
    if next_token == tokenizer.eos_token_id:
        break

text = tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
print(f'\nResult: {repr(text[:200])}')
