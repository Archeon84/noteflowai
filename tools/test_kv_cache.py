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

# Step 0: full prompt, no cache
feed = {}
feed['input_ids'] = np.array([prompt], dtype=np.int64)
feed['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed['use_cache_branch'] = np.array([False], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name not in feed:
        feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)

results = dec.run(None, feed)
logits0 = results[0]
print(f'Step 0 logits shape: {logits0.shape}')

# Examine ALL outputs
for i, name in enumerate(dec_output_names):
    tensor = results[i]
    arr = tensor.numpy() if hasattr(tensor, 'numpy') else np.array(tensor)
    print(f'  output[{i}] {name}: shape={arr.shape} range=[{arr.min():.6f}, {arr.max():.6f}] mean={arr.mean():.6f}')

# Check which outputs have non-zero encoder KV
for i, name in enumerate(dec_output_names):
    if 'encoder.key' in name or 'encoder.value' in name:
        arr = results[i].numpy() if hasattr(results[i], 'numpy') else np.array(results[i])
        nonzero = np.count_nonzero(arr)
        total = arr.size
        print(f'  {name}: nonzero={nonzero}/{total} ({nonzero/total*100:.1f}%)')

# Step 1: use cache, single token
# The critical question: are encoder KV caches populated after step 0?
past_kv = {}
for i, name in enumerate(dec_output_names):
    if name.startswith('present.'):
        past_name = name.replace('present.', 'past_key_values.')
        arr = results[i].numpy() if hasattr(results[i], 'numpy') else np.array(results[i])
        past_kv[past_name] = arr
        if 'encoder' in name:
            nonzero = np.count_nonzero(arr)
            total = arr.size
            print(f'  cached {past_name}: shape={arr.shape} nonzero={nonzero}/{total} ({nonzero/total*100:.1f}%)')

next_logits = logits0[0, -1, :]
next_logits[suppress_from:] = -1e9
next_token = int(np.argmax(next_logits))
print(f'\nStep 0 prediction: token={next_token} ({repr(tokenizer.decode([next_token]))})')

# Step 1 with proper KV cache
feed1 = {}
feed1['input_ids'] = np.array([[next_token]], dtype=np.int64)
feed1['encoder_hidden_states'] = onnx_enc.astype(np.float32)
feed1['use_cache_branch'] = np.array([True], dtype=np.bool_)
for inp_name in dec_inputs:
    if inp_name in feed1:
        continue
    if inp_name in past_kv:
        feed1[inp_name] = past_kv[inp_name]
    else:
        feed1[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)

results1 = dec.run(None, feed1)
logits1 = results1[0]
next_logits1 = logits1[0, -1, :]
next_logits1[suppress_from:] = -1e9
next_token1 = int(np.argmax(next_logits1))
print(f'Step 1 prediction: token={next_token1} ({repr(tokenizer.decode([next_token1]))})')
print(f'Step 1 top5: {[(int(t), tokenizer.decode([t]), float(next_logits1[t])) for t in np.argsort(next_logits1)[-5:][::-1]]}')
