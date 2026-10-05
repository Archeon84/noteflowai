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

tokens = list(prompt)
past_kv_decoder = {}

for step in range(50):
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
            # NEVER pass encoder KV - let model recompute from encoder_hidden_states
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
        elif 'decoder' in inp_name:
            if inp_name in past_kv_decoder:
                feed[inp_name] = past_kv_decoder[inp_name]
            else:
                feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
        else:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    
    results = dec.run(None, feed)
    logits = results[0]
    
    # Only cache decoder KV, NOT encoder KV
    past_kv_decoder = {}
    for i, name in enumerate(dec_output_names):
        if name.startswith('present.') and 'decoder' in name:
            past_kv_decoder[name.replace('present.', 'past_key_values.')] = results[i]
    
    next_logits = logits[0, -1, :]
    next_logits[suppress_from:] = -1e9
    next_token = int(np.argmax(next_logits))
    tokens.append(next_token)
    
    text = tokenizer.decode([next_token])
    if step < 30 or next_token == tokenizer.eos_token_id:
        print(f'  step {step}: id={next_token} text={repr(text)}')
    if next_token == tokenizer.eos_token_id:
        break

text = tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
print(f'\nResult: {repr(text[:300])}')
