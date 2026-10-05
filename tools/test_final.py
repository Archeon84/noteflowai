import sys, os, numpy as np, wave
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from transformers import WhisperFeatureExtractor, WhisperTokenizer, WhisperForConditionalGeneration
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

# Get HF processor features (with normalization)
result = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
hf_features = result.input_features[0].astype(np.float32)
print(f'HF features: [{hf_features.min():.4f}, {hf_features.max():.4f}]')

# Simulate our Kotlin mel computation with normalization
N_FFT = 400; HOP = 160; N_MEL = 80; N_FRAMES = 3000
padded = np.zeros(sr * 30, dtype=np.float32)
copy_len = min(len(pcmf), len(padded))
padded[:copy_len] = pcmf[:copy_len]
audio_padded = np.pad(padded, (N_FFT // 2, N_FFT // 2), mode='reflect')
window = np.hanning(N_FFT + 1)[:-1].astype(np.float64)

mel_filters = feat_extractor.mel_filters.T  # [201, 80] -> [201, 80] ... wait

# Actually check shape
print(f'mel_filters shape: {feat_extractor.mel_filters.shape}')

n_frames = 1 + (len(audio_padded) - N_FFT) // HOP
stft = np.zeros((n_frames, N_FFT // 2 + 1), dtype=np.complex128)
for i in range(n_frames):
    frame = audio_padded[i*HOP:i*HOP+N_FFT].astype(np.float64) * window
    stft[i] = np.fft.rfft(frame)

magnitude = np.abs(stft) ** 2

# mel_filters is [80, 201], magnitude is [n_frames, 201]
mel_filters_np = feat_extractor.mel_filters  # [201, 80]
mel_energy = magnitude @ mel_filters_np  # [n_frames, 201] @ [201, 80] = [n_frames, 80]
log_mel = np.log(mel_energy + 1e-9)  # [n_frames, 80]

# Pad to 3000 frames
if log_mel.shape[0] < N_FRAMES:
    log_mel = np.pad(log_mel, ((0, N_FRAMES - log_mel.shape[0]), (0,0)))
log_mel = log_mel[:N_FRAMES, :N_MEL].T.astype(np.float32)  # [80, 3000]

print(f'Raw mel: [{log_mel.min():.4f}, {log_mel.max():.4f}]')

# Apply per-frame zero-mean unit-var normalization (simulating Kotlin code)
for frame in range(N_FRAMES):
    col = log_mel[:, frame]
    mean = col.mean()
    std = col.std() + 1e-8
    log_mel[:, frame] = (col - mean) / std

print(f'Normalized: [{log_mel.min():.4f}, {log_mel.max():.4f}]')
diff = np.abs(log_mel - hf_features)
print(f'Diff vs HF: max={diff.max():.6f}, mean={diff.mean():.6f}')

# Test ONNX with our normalized features
enc = ort.InferenceSession('onnx_models/tiny/onnx/encoder_model.onnx')
onnx_enc = enc.run(None, {enc.get_inputs()[0].name: log_mel.reshape(1, 80, 3000)})[0]
print(f'ONNX encoder with our mel: mean={onnx_enc.mean():.6f}')

# Now test full transcription
dec = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = {inp.name: inp for inp in dec.get_inputs()}
dec_output_names = [out.name for out in dec.get_outputs()]

sot = tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
lang_en = tokenizer.convert_tokens_to_ids('<|en|>')
task_transcribe = tokenizer.convert_tokens_to_ids('<|transcribe|>')
notimestamps = tokenizer.convert_tokens_to_ids('<|notimestamps|>')
prompt = [sot, lang_en, task_transcribe, notimestamps]

tokens = list(prompt)
past_kv = {}
suppress_from = 50257

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
        if inp_name in past_kv:
            feed[inp_name] = past_kv[inp_name]
        else:
            feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)
    
    results = dec.run(None, feed)
    logits = results[0]
    past_kv = {}
    for i, name in enumerate(dec_output_names):
        if name.startswith('present.'):
            past_kv[name.replace('present.', 'past_key_values.')] = results[i]
    
    next_logits = logits[0, -1, :]
    
    # Suppress special tokens
    next_logits[suppress_from:] = -1e9
    
    next_token = int(np.argmax(next_logits))
    tokens.append(next_token)
    
    text = tokenizer.decode([next_token])
    print(f'  step {step}: id={next_token} text={repr(text)} logit={next_logits[next_token]:.2f}')
    
    if next_token == tokenizer.eos_token_id:
        break

text = tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
print(f'\nTranscription: {repr(text)}')
