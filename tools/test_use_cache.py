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

sot = tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
lang_en = tokenizer.convert_tokens_to_ids('<|en|>')
task_transcribe = tokenizer.convert_tokens_to_ids('<|transcribe|>')
notimestamps = tokenizer.convert_tokens_to_ids('<|notimestamps|>')
prompt = [sot, lang_en, task_transcribe, notimestamps]

suppress_from = 50257

# Test: what happens if use_cache is always True?
print('=== Test: use_cache always True ===')
tokens = list(prompt)
past_kv = {}

for step in range(15):
    ids = np.array([[tokens[-1]]], dtype=np.int64)
    
    feed = {}
    feed['input_ids'] = ids
    feed['encoder_hidden_states'] = onnx_enc.astype(np.float32)
    feed['use_cache_branch'] = np.array([True], dtype=np.bool_)
    
    for inp_name in dec_inputs:
        if inp_name in feed:
            continue
        if inp_name in past_kv:
            feed[inp_name] = past_kv[inp_name]
        else:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    
    results = dec.run(None, feed)
    logits = results[0]
    past_kv = {}
    for i, name in enumerate(dec_output_names):
        if name.startswith('present.'):
            past_kv[name.replace('present.', 'past_key_values.')] = results[i] if isinstance(results[i], np.ndarray) else results[i].numpy()
    
    next_logits = logits[0, -1, :]
    next_logits[suppress_from:] = -1e9
    next_token = int(np.argmax(next_logits))
    tokens.append(next_token)
    
    text = tokenizer.decode([next_token])
    print(f'  step {step}: id={next_token} text={repr(text)}')

text = tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
print(f'Result: {repr(text[:200])}')

# Test 2: the actual correct way - step 0 with use_cache=False, step 1+ with use_cache=True
# BUT also check: does the ONNX model even USE the use_cache_branch input?
print('\n=== Compare: use_cache=False at step0 vs use_cache=True at step0 ===')
# Run step 0 with use_cache=False
feed_a = {}
feed_a['input_ids'] = np.array([prompt], dtype=np.int64)
feed_a['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed_a['use_cache_branch'] = np.array([False], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name not in feed_a:
        feed_a[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
results_a = dec.run(None, feed_a)
logits_a = results_a[0]

# Run step 0 with use_cache=True
feed_b = {}
feed_b['input_ids'] = np.array([prompt], dtype=np.int64)
feed_b['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed_b['use_cache_branch'] = np.array([True], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name not in feed_b:
        feed_b[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
results_b = dec.run(None, feed_b)
logits_b = results_b[0]

diff = np.abs(logits_a - logits_b)
print(f'step0 use_cache=False vs True: logits diff max={diff.max():.6f}, mean={diff.mean():.6f}')
if diff.max() < 0.001:
    print('IDENTICAL - use_cache_branch is IGNORED! The traced graph treats it as a constant.')
else:
    print('DIFFERENT - use_cache_branch works correctly.')
    
# Check the encoder KV outputs too
for i, name in enumerate(dec_output_names):
    if 'encoder.key' in name:
        arr_a = results_a[i] if isinstance(results_a[i], np.ndarray) else results_a[i].numpy()
        arr_b = results_b[i] if isinstance(results_b[i], np.ndarray) else results_b[i].numpy()
        ecdiff = np.abs(arr_a - arr_b)
        print(f'  {name} diff: max={ecdiff.max():.6f}')
