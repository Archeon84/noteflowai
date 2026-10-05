import sys, os, numpy as np, wave, json
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from transformers import WhisperFeatureExtractor
import wave

feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')

# Load audio
with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

# Get HF processor features
result = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='np', return_attention_mask=False)
hf_features = result.input_features[0]
print(f'HF features: [{hf_features.min():.4f}, {hf_features.max():.4f}]')

# Now manually compute the mel WITHOUT normalization
# Step 1: Build STFT using numpy
N_FFT = 400
HOP = 160

padded = np.zeros(sr * 30, dtype=np.float32)
copy_len = min(len(pcmf), len(padded))
padded[:copy_len] = pcmf[:copy_len]
audio_padded = np.pad(padded, (N_FFT // 2, N_FFT // 2), mode='reflect')

window = np.hanning(N_FFT + 1)[:-1].astype(np.float64)
n_frames_stft = 1 + (len(audio_padded) - N_FFT) // HOP

# Use numpy STFT
stft_result = np.zeros((n_frames_stft, N_FFT // 2 + 1), dtype=np.complex128)
for i in range(n_frames_stft):
    frame = audio_padded[i*HOP:i*HOP+N_FFT].astype(np.float64) * window
    stft_result[i] = np.fft.rfft(frame)

magnitude = np.abs(stft_result) ** 2  # [n_frames, 201]

# Step 2: Mel filterbank from HF config
mel_filters = feat_extractor.mel_filters  # might be [80, 201] or [201, 80]
print(f'mel_filters shape: {mel_filters.shape}')
if mel_filters.shape[0] == 80:
    mel_energy = magnitude @ mel_filters.T  # [n_frames, 201] @ [201, 80] = [n_frames, 80]
else:
    mel_energy = magnitude @ mel_filters  # [n_frames, 201] @ [201, 80] = [n_frames, 80]
log_mel = np.log(mel_energy + 1e-9)  # [n_frames, 80]
log_mel = log_mel.T  # [80, n_frames]

# Pad to 3000 frames
N_FRAMES = 3000
if log_mel.shape[1] < N_FRAMES:
    log_mel = np.pad(log_mel, ((0,0),(0, N_FRAMES - log_mel.shape[1])))
log_mel = log_mel[:80, :N_FRAMES].astype(np.float32)

print(f'Our mel: [{log_mel.min():.4f}, {log_mel.max():.4f}]')

# Step 3: Apply zero_mean_unit_var_norm
attn_mask = np.ones((1, 3000), dtype=np.float32)
normed = np.array(feat_extractor.zero_mean_unit_var_norm(log_mel, attn_mask))
print(f'After zero_mean_unit_var_norm: [{normed.min():.4f}, {normed.max():.4f}], shape={normed.shape}')

# Compare
diff = np.abs(normed - hf_features)
print(f'Normalized vs HF: max_diff={diff.max():.6f}, mean_diff={diff.mean():.6f}')

if diff.max() < 0.01:
    print('MATCH! The normalization fixes everything!')
else:
    print(f'Still mismatch. Analyzing...')
    # Check the first 5 values of each
    print(f'Our normed [0,:5]: {normed[0,:5]}')
    print(f'HF features [0,:5]: {hf_features[0,:5]}')
    print(f'Our raw    [0,:5]: {log_mel[0,:5]}')
    
    # Try: maybe the STFT uses different window or padding
    # Try using torch.stft instead
    import torch
    import torch.nn.functional as F
    
    pcm_tensor = torch.from_numpy(pcmf)
    # Pad to same length
    pcm_padded = F.pad(pcm_tensor, (N_FFT//2, N_FFT//2), mode='reflect')
    
    # Torch STFT
    window_torch = torch.hann_window(N_FFT)
    stft_torch = torch.stft(
        pcm_padded, N_FFT, hop_length=HOP, win_length=N_FFT, 
        window=window_torch, center=False, pad_mode='reflect',
        return_complex=True
    )
    mag_torch = stft_torch.abs() ** 2  # [1, 201, n_frames]
    mag_torch = mag_torch.numpy()[0].T  # [n_frames, 201]
    
    mel_torch = mag_torch @ mel_filters.T if mel_filters.shape[0] == 80 else mag_torch @ mel_filters  # [n_frames, 80]
    log_mel_torch = np.log(mel_torch + 1e-9).T  # [80, n_frames]
    
    if log_mel_torch.shape[1] < N_FRAMES:
        log_mel_torch = np.pad(log_mel_torch, ((0,0),(0, N_FRAMES - log_mel_torch.shape[1])))
    log_mel_torch = log_mel_torch[:80, :N_FRAMES].astype(np.float32)
    
    print(f'\nTorch STFT mel: [{log_mel_torch.min():.4f}, {log_mel_torch.max():.4f}]')
    
    diff_torch = np.abs(log_mel_torch - log_mel)
    print(f'Torch STFT vs numpy STFT: max_diff={diff_torch.max():.6f}, mean_diff={diff_torch.mean():.6f}')
    
    # Apply norm to torch version
    normed_torch = np.array(feat_extractor.zero_mean_unit_var_norm(log_mel_torch, attn_mask))
    diff_torch_norm = np.abs(normed_torch - hf_features)
    print(f'Torch normed vs HF: max_diff={diff_torch_norm.max():.6f}, mean_diff={diff_torch_norm.mean():.6f}')
    
    if diff_torch_norm.max() < 0.01:
        print('MATCH with torch STFT!')
    else:
        print(f'Torch STFT also mismatch. First few values:')
        print(f'  Torch normed [0,:5]: {normed_torch[0,:5]}')
        print(f'  HF features  [0,:5]: {hf_features[0,:5]}')
