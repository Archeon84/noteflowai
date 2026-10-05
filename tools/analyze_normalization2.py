import sys, os, numpy as np, wave, json
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from transformers import WhisperFeatureExtractor
import wave

feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')
print(f'Feature extractor type: {type(feat_extractor).__name__}')
print(f'All attrs: {[a for a in dir(feat_extractor) if not a.startswith("_")]}')

# Load audio
with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

print(f'Audio: {len(pcmf)/sr:.2f}s')

# Get HF processor features
result = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
hf_features = result.input_features[0]  # [80, 3000]
print(f'HF features: shape={hf_features.shape}, range=[{hf_features.min():.4f}, {hf_features.max():.4f}]')

# Check the mel filterbank
fb = feat_extractor.mel_filters
print(f'\nMel filterbank shape: {fb.shape}')
print(f'Mel filterbank range: [{fb.min():.6f}, {fb.max():.6f}]')
print(f'Mel filterbank sum per row: min={fb.sum(axis=1).min():.6f}, max={fb.sum(axis=1).max():.6f}')

# Access preprocessor config
from huggingface_hub import hf_hub_download
config_path = hf_hub_download('openai/whisper-tiny', 'preprocessor_config.json')
with open(config_path) as f:
    cfg = json.load(f)

print(f'\nPreprocessor config:')
print(f'  zero_mean_unit_var_norm: {feat_extractor.zero_mean_unit_var_norm}')
for k, v in cfg.items():
    if k != 'mel_filters':
        print(f'  {k}: {v}')
    else:
        print(f'  mel_filters: [{len(v)} bands, {len(v[0])} freqs]')

# Now check the actual mel computation step by step
N_FFT = cfg['n_fft']
HOP = cfg['hop_length']
N_MEL = cfg['feature_size']
N_FRAMES = cfg['nb_max_frames']
print(f'\nParams from config: n_fft={N_FFT}, hop_length={HOP}, num_mel_bins={N_MEL}, nb_max_frames={N_FRAMES}')

# Build STFT
padded = np.zeros(sr * 30, dtype=np.float32)
copy_len = min(len(pcmf), len(padded))
padded[:copy_len] = pcmf[:copy_len]
audio_padded = np.pad(padded, (N_FFT // 2, N_FFT // 2), mode='reflect')

window = np.hanning(N_FFT + 1)[:-1].astype(np.float64)
n_frames = 1 + (len(audio_padded) - N_FFT) // HOP
stft = np.zeros((n_frames, N_FFT // 2 + 1), dtype=np.complex128)
for i in range(n_frames):
    frame = audio_padded[i*HOP:i*HOP+N_FFT].astype(np.float64) * window
    stft[i] = np.fft.rfft(frame)

magnitude = np.abs(stft) ** 2  # [n_frames, n_freq]

# Apply mel filterbank from HF config
fb_np = np.array(cfg['mel_filters'], dtype=np.float64)
mel_energy = magnitude @ fb_np.T  # [n_frames, N_MEL]
log_mel = np.log(mel_energy + 1e-9).T  # [N_MEL, n_frames]

# Pad to N_FRAMES
if log_mel.shape[1] < N_FRAMES:
    log_mel = np.pad(log_mel, ((0,0),(0, N_FRAMES - log_mel.shape[1])))
log_mel = log_mel[:N_MEL, :N_FRAMES].astype(np.float32)

print(f'\nOur mel (with HF filterbank): range=[{log_mel.min():.4f}, {log_mel.max():.4f}]')

# Check if the diff is due to normalization or mel computation
diff_raw = np.abs(log_mel - hf_features)
print(f'Raw vs HF: max_diff={diff_raw.max():.6f}, mean_diff={diff_raw.mean():.6f}')

# Check per-frame normalization
frame_means = log_mel.mean(axis=0, keepdims=True)  # [1, 3000]
frame_stds = log_mel.std(axis=0, keepdims=True)
normed = (log_mel - frame_means) / (frame_stds + 1e-8)
diff_normed = np.abs(normed - hf_features)
print(f'Per-frame normed vs HF: max_diff={diff_normed.max():.6f}, mean_diff={diff_normed.mean():.6f}')

# Check if it's global normalization
gmean = log_mel.mean()
gstd = log_mel.std()
normed_g = (log_mel - gmean) / gstd
diff_g = np.abs(normed_g - hf_features)
print(f'Global normed vs HF: max_diff={diff_g.max():.6f}, mean_diff={diff_g.mean():.6f}')

# Show per-frame stats
print(f'\nPer-frame means: min={log_mel.mean(axis=0).min():.4f}, max={log_mel.mean(axis=0).max():.4f}')
print(f'Per-frame stds: min={log_mel.std(axis=0).min():.4f}, max={log_mel.std(axis=0).max():.4f}')
print(f'HF per-frame means: min={hf_features.mean(axis=0).min():.4f}, max={hf_features.mean(axis=0).max():.4f}')
print(f'HF per-frame stds: min={hf_features.std(axis=0).min():.4f}, max={hf_features.std(axis=0).max():.4f}')
