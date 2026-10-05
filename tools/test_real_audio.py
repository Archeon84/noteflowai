"""Compare Android mel vs HuggingFace WhisperProcessor on the user's actual recording."""
import numpy as np
import wave, struct, json, os, sys

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

def load_wav_android_style(path):
    """Load WAV same as WhisperNpuEngine.loadWav()"""
    with wave.open(path, 'rb') as wf:
        sr = wf.getframerate()
        ch = wf.getnchannels()
        bits = wf.getsampwidth() * 8
        n = wf.getnframes()
        raw = wf.readframes(n)
        print(f"WAV: {sr}Hz {ch}ch {bits}bit {n} frames ({n/sr:.1f}s)")
        
        if bits == 16:
            samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
        else:
            raise ValueError(f"Unsupported bits: {bits}")
        
        max_amp = np.max(np.abs(samples))
        scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
        pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)
        print(f"PCM: peak={np.max(np.abs(pcmf)):.4f} rms={np.sqrt(np.mean(pcmf**2)):.4f} nonzero={np.count_nonzero(np.abs(pcmf) > 0.001)/len(pcmf):.2f}")
        return pcmf, sr

def android_mel(pcmf, sr=16000):
    """Exact replica of Android WhisperMelSpectrogram.kt (with ln(mel+1e-9) fix)"""
    N_FFT = 400; HOP = 160; N_MEL = 80; N_FRAMES = 3000; N_SAMPLES = sr * 30
    
    padded = np.zeros(N_SAMPLES, dtype=np.float32)
    copy_len = min(len(pcmf), N_SAMPLES)
    padded[:copy_len] = pcmf[:copy_len]
    
    pad_len = N_FFT // 2
    padded_reflect = np.zeros(N_SAMPLES + N_FFT, dtype=np.float32)
    # Fixed left reflection (off-by-one corrected)
    for i in range(pad_len):
        padded_reflect[i] = padded[pad_len - 1 - i]
    padded_reflect[pad_len:pad_len + N_SAMPLES] = padded
    for i in range(pad_len):
        padded_reflect[N_SAMPLES + pad_len + i] = padded[N_SAMPLES - 1 - i]
    
    # Periodic Hann window
    hann = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(N_FFT) / N_FFT)).astype(np.float32)
    
    # Direct DFT
    n_freq = N_FFT // 2 + 1
    dft_cos = np.zeros((n_freq, N_FFT), dtype=np.float32)
    dft_sin = np.zeros((n_freq, N_FFT), dtype=np.float32)
    for k in range(n_freq):
        for n in range(N_FFT):
            angle = 2.0 * np.pi * k * n / N_FFT
            dft_cos[k, n] = np.cos(angle).astype(np.float32)
            dft_sin[k, n] = (-np.sin(angle)).astype(np.float32)
    
    # Mel filterbank (linear scale)
    f_sp = 200.0 / 3.0
    low_mel = 0.0; high_mel = (sr / 2.0) / f_sp
    mel_pts = np.array([low_mel + i * (high_mel - low_mel) / (N_MEL + 1) for i in range(N_MEL + 2)], dtype=np.float32)
    hz_pts = mel_pts * f_sp
    
    fb = np.zeros((N_MEL, n_freq), dtype=np.float32)
    for mel in range(N_MEL):
        lo = hz_pts[mel]; ct = hz_pts[mel+1]; hi = hz_pts[mel+2]
        for k in range(n_freq):
            freq = float(k) * sr / N_FFT
            if freq < lo: fb[mel,k] = 0.0
            elif lo <= freq <= ct: fb[mel,k] = (freq - lo) / (ct - lo) if ct > lo else 0.0
            elif ct < freq <= hi: fb[mel,k] = (hi - freq) / (hi - ct) if hi > ct else 0.0
            else: fb[mel,k] = 0.0
    
    result = np.zeros(N_MEL * N_FRAMES, dtype=np.float32)
    for frame in range(N_FRAMES):
        offset = frame * HOP
        fft_real = (padded_reflect[offset:offset + N_FFT].copy() * hann).astype(np.float32)
        power = np.zeros(n_freq, dtype=np.float32)
        for k in range(n_freq):
            re = np.sum(fft_real * dft_cos[k])
            im = np.sum(fft_real * dft_sin[k])
            power[k] = re * re + im * im
        for mel in range(N_MEL):
            mel_energy = np.sum(power * fb[mel])
            result[mel * N_FRAMES + frame] = np.log(mel_energy + 1e-9)
    
    mel_2d = result.reshape(N_MEL, N_FRAMES)
    print(f"Android mel: min={mel_2d.min():.4f} max={mel_2d.max():.4f} mean={mel_2d.mean():.4f}")
    return mel_2d

def hf_mel(pcmf, sr=16000):
    """HuggingFace WhisperFeatureExtractor style"""
    N_FFT = 400; HOP = 160; N_MEL = 80; N_FRAMES = 3000; N_SAMPLES = sr * 30
    
    padded = np.zeros(N_SAMPLES, dtype=np.float32)
    copy_len = min(len(pcmf), N_SAMPLES)
    padded[:copy_len] = pcmf[:copy_len]
    
    audio_padded = np.pad(padded, (N_FFT//2, N_FFT//2), mode='reflect')
    n_frames = 1 + (len(audio_padded) - N_FFT) // HOP
    
    # Periodic Hann (HuggingFace uses np.hanning(n_fft+1)[:-1])
    window = np.hanning(N_FFT + 1)[:-1].astype(np.float32)
    
    stft = np.zeros((n_frames, N_FFT // 2 + 1), dtype=np.float64)
    for i in range(n_frames):
        start = i * HOP
        frame = audio_padded[start:start + N_FFT].astype(np.float64) * window.astype(np.float64)
        stft[i] = np.fft.rfft(frame)
    
    magnitude = np.abs(stft) ** 2
    
    # Mel filterbank
    f_sp = 200.0 / 3.0
    low_mel = 0.0; high_mel = (sr / 2.0) / f_sp
    mel_pts = np.linspace(low_mel, high_mel, N_MEL + 2)
    hz_pts = mel_pts * f_sp
    n_freq = N_FFT // 2 + 1
    
    fb = np.zeros((N_MEL, n_freq), dtype=np.float64)
    for i in range(N_MEL):
        lo = hz_pts[i]; ct = hz_pts[i+1]; hi = hz_pts[i+2]
        for j in range(n_freq):
            freq = j * sr / N_FFT
            if lo <= freq <= ct and ct > lo:
                fb[i,j] = (freq - lo) / (ct - lo)
            elif ct < freq <= hi and hi > ct:
                fb[i,j] = (hi - freq) / (hi - ct)
    
    mel_energy = magnitude @ fb.T
    log_mel = np.log(mel_energy + 1e-9).T  # [N_MEL, n_frames]
    
    if log_mel.shape[1] > N_FRAMES:
        log_mel = log_mel[:, :N_FRAMES]
    elif log_mel.shape[1] < N_FRAMES:
        log_mel = np.pad(log_mel, ((0,0),(0, N_FRAMES - log_mel.shape[1])))
    
    print(f"HF mel: min={log_mel.min():.4f} max={log_mel.max():.4f} mean={log_mel.mean():.4f}")
    return log_mel.astype(np.float32)

# Load user's recording
pcmf, sr = load_wav_android_style("test_audio.wav")

# Compute both mel styles
mel_android = android_mel(pcmf, sr)
mel_hf = hf_mel(pcmf, sr)

# Compare
diff = np.abs(mel_android - mel_hf)
print(f"\nMel diff: max={diff.max():.4f} mean={diff.mean():.4f}")
# Show where differences are large
large_diff_mask = diff > 1.0
print(f"Frames with diff > 1.0: {large_diff_mask.sum()} / {diff.size} ({100*large_diff_mask.mean():.1f}%)")

# Run encoder
import onnxruntime as ort
enc = ort.InferenceSession("onnx_models/tiny/onnx/encoder_model.onnx")
enc_in = enc.get_inputs()[0]

hidden_android = enc.run(None, {enc_in.name: mel_android.reshape(1, 80, 3000)})[0]
hidden_hf = enc.run(None, {enc_in.name: mel_hf.reshape(1, 80, 3000)})[0]

enc_diff = np.abs(hidden_android - hidden_hf)
print(f"\nEncoder diff: max={enc_diff.max():.4f} mean={enc_diff.mean():.4f}")
print(f"Android enc: mean={hidden_android.mean():.6f} absMax={np.abs(hidden_android).max():.6f}")
print(f"HF enc:      mean={hidden_hf.mean():.6f} absMax={np.abs(hidden_hf).max():.6f}")

# Run decoder step 0 with both
dec = ort.InferenceSession("onnx_models/tiny/onnx/decoder_model_merged.onnx")
dec_inputs = dec.get_inputs()
prompt = np.array([[50258, 50259, 50359, 50363]], dtype=np.int64)

def run_decoder_step0(hidden, label):
    feed = {}
    for inp in dec_inputs:
        n = inp.name
        if n == "input_ids": feed[n] = prompt
        elif "encoder_hidden" in n: feed[n] = hidden
        elif "past" in n: feed[n] = np.zeros([1, 6, 0, 64], dtype=np.float32)
        elif "use_cache" in n: feed[n] = np.array([False], dtype=np.bool_)
    res = dec.run(None, feed)
    logits = res[0][0, -1, :]
    top10 = np.argsort(logits)[-10:][::-1]
    print(f"\n{label} decoder step 0 top 10:")
    for idx in top10:
        print(f"  token {idx}: {logits[idx]:.4f}")

run_decoder_step0(hidden_android, "Android mel")
run_decoder_step0(hidden_hf, "HF mel")

# Also try: what does the official HuggingFace pipeline produce?
print("\n--- Testing with transformers WhisperProcessor ---")
try:
    from transformers import WhisperProcessor, WhisperForConditionalGeneration
    processor = WhisperProcessor.from_pretrained("openai/whisper-tiny")
    model = WhisperForConditionalGeneration.from_pretrained("openai/whisper-tiny")
    
    # Re-load audio at 16kHz
    import soundfile as sf
    audio_data, sr_orig = sf.read("test_audio.wav")
    if sr_orig != 16000:
        import scipy.signal
        audio_data = scipy.signal.resample(audio_data, int(len(audio_data) * 16000 / sr_orig))
    
    input_features = processor(audio_data, sampling_rate=16000, return_tensors="np").input_features
    print(f"HF input_features shape: {input_features.shape}, range: [{input_features.min():.4f}, {input_features.max():.4f}]")
    
    # Compare mel values
    print(f"Android mel[0,:5] = {mel_android[0,:5]}")
    print(f"HF proc mel[0,:5] = {input_features[0, 0, :5]}")
    
    predicted_ids = model.generate(input_features)
    transcription = processor.batch_decode(predicted_ids, skip_special_tokens=True)
    print(f"HF pipeline result: '{transcription[0]}'")
except Exception as e:
    print(f"HF pipeline test failed: {e}")
