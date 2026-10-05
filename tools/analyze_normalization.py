import sys, os, numpy as np, wave
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Restore transformers version
import importlib
import transformers
print(f'transformers version: {transformers.__version__}')

from transformers import WhisperFeatureExtractor
import wave

feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')
print(f'do_normalize: {feat_extractor.do_normalize}')
print(f'feature_size: {feat_extractor.feature_size}')
print(f'nb_max_frames: {feat_extractor.nb_max_frames}')
print(f'chunk_length: {feat_extractor.chunk_length}')
print(f'sampling_rate: {feat_extractor.sampling_rate}')
print(f'hop_length: {feat_extractor.hop_length}')
print(f'n_fft: {feat_extractor.n_fft}')

# Load audio
with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

print(f'\nAudio: {len(pcmf)/sr:.2f}s')

# Get HF processor features
result = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
hf_features = result.input_features[0]  # [80, 3000]
print(f'HF features: shape={hf_features.shape}, range=[{hf_features.min():.4f}, {hf_features.max():.4f}], mean={hf_features.mean():.4f}, std={hf_features.std():.4f}')

# Now compute our mel spectrogram
N_FFT = feat_extractor.n_fft
HOP = feat_extractor.hop_length
N_MEL = feat_extractor.feature_size
N_FRAMES = feat_extractor.nb_max_frames
print(f'\nParams: N_FFT={N_FFT}, HOP={HOP}, N_MEL={N_MEL}, N_FRAMES={N_FRAMES}')

# Step 1: Pad audio
padded = np.zeros(sr * 30, dtype=np.float32)
copy_len = min(len(pcmf), len(padded))
padded[:copy_len] = pcmf[:copy_len]
audio_padded = np.pad(padded, (N_FFT // 2, N_FFT // 2), mode='reflect')

# Step 2: Compute STFT
window = np.hanning(N_FFT + 1)[:-1].astype(np.float64)
n_frames = 1 + (len(audio_padded) - N_FFT) // HOP
stft = np.zeros((n_frames, N_FFT // 2 + 1), dtype=np.complex128)
for i in range(n_frames):
    frame = audio_padded[i*HOP:i*HOP+N_FFT].astype(np.float64) * window
    stft[i] = np.fft.rfft(frame)

magnitude = np.abs(stft) ** 2

# Step 3: Mel filterbank (linear scale, htk=False)
f_sp = 200.0 / 3.0
mel_pts = np.linspace(0, (sr/2)/f_sp, N_MEL + 2)
hz_pts = mel_pts * f_sp
n_freq = N_FFT // 2 + 1
fb = np.zeros((N_MEL, n_freq), dtype=np.float64)
for i in range(N_MEL):
    lo, ct, hi = hz_pts[i], hz_pts[i+1], hz_pts[i+2]
    for j in range(n_freq):
        freq = j * sr / N_FFT
        if lo <= freq <= ct and ct > lo:
            fb[i,j] = (freq - lo) / (ct - lo)
        elif ct < freq <= hi and hi > ct:
            fb[i,j] = (hi - freq) / (hi - ct)

mel_energy = magnitude @ fb.T
log_mel = np.log(mel_energy + 1e-9).T  # [n_frames, 80]

# Pad to 3000 frames
if log_mel.shape[1] < N_FRAMES:
    log_mel = np.pad(log_mel, ((0,0),(0, N_FRAMES - log_mel.shape[1])))
log_mel = log_mel[:N_MEL, :N_FRAMES].astype(np.float32)

print(f'\nOur raw mel: range=[{log_mel.min():.4f}, {log_mel.max():.4f}], mean={log_mel.mean():.4f}')
print(f'HF features: range=[{hf_features.min():.4f}, {hf_features.max():.4f}], mean={hf_features.mean():.4f}')

# Compute the normalization: HF = (our_raw - mean) / std per column (per frame, over 80 bands)
# Or is it global?
# Check per-frame normalization
frame_means = log_mel.mean(axis=0)  # [3000]
frame_stds = log_mel.std(axis=0)    # [3000]
print(f'\nPer-frame stats:')
print(f'  means: min={frame_means.min():.4f}, max={frame_means.max():.4f}, mean={frame_means.mean():.4f}')
print(f'  stds:  min={frame_stds.min():.4f}, max={frame_stds.max():.4f}, mean={frame_stds.mean():.4f}')

# Try applying per-frame normalization
normed_per_frame = (log_mel - frame_means) / (frame_stds + 1e-5)
print(f'\nPer-frame normalized: range=[{normed_per_frame.min():.4f}, {normed_per_frame.max():.4f}]')

# Try global normalization
global_mean = log_mel.mean()
global_std = log_mel.std()
normed_global = (log_mel - global_mean) / global_std
print(f'Global normalized: range=[{normed_global.min():.4f}, {normed_global.max():.4f}]')

# Compare with HF
diff_pf = np.abs(normed_per_frame - hf_features)
diff_gl = np.abs(normed_global - hf_features)
print(f'\nPer-frame vs HF: max_diff={diff_pf.max():.6f}, mean_diff={diff_pf.mean():.6f}')
print(f'Global vs HF: max_diff={diff_gl.max():.6f}, mean_diff={diff_gl.mean():.6f}')

# Try per-band normalization (over time, per mel band)
band_means = log_mel.mean(axis=1, keepdims=True)  # [80, 1]
band_stds = log_mel.std(axis=1, keepdims=True)
normed_band = (log_mel - band_means) / (band_stds + 1e-5)
diff_band = np.abs(normed_band - hf_features)
print(f'Per-band vs HF: max_diff={diff_band.max():.6f}, mean_diff={diff_band.mean():.6f}')

# Check: maybe the HF processor does NOT normalize, and the difference is in the mel computation itself
# Let me check if our mel computation differs from HF's
# The HF processor uses torch.stft, which might differ from our manual FFT

# Check the mel filterbank from the processor
if hasattr(feat_extractor, 'mel_filter_bank'):
    print(f'\nHF mel filterbank: {feat_extractor.mel_filter_bank.shape}')
elif hasattr(feat_extractor, 'filters'):
    print(f'\nHF filters: {feat_extractor.filters.shape}')
else:
    # Access via feature_extractor_config
    import json
    config_path = os.path.join(os.path.expanduser('~'), '.cache', 'huggingface', 'hub', 'models--openai--whisper-tiny')
    for root, dirs, files in os.walk(config_path):
        for f in files:
            if 'preprocessor_config' in f:
                with open(os.path.join(root, f)) as fp:
                    cfg = json.load(fp)
                print(f'\nPreprocessor config keys: {list(cfg.keys())}')
                for k, v in cfg.items():
                    if k != 'mel_filter_bank':
                        print(f'  {k}: {v}')
                break
