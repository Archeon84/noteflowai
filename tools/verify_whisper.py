"""Verify Whisper ONNX encoder output and decoder step 0 with HuggingFace preprocessing reference."""
import numpy as np
import onnxruntime as ort
import json, os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

ENCODER_PATH = "onnx_models/tiny/onnx/encoder_model.onnx"
DECODER_PATH = "onnx_models/tiny/onnx/decoder_model_merged.onnx"
VOCAB_PATH = "onnx_models/tiny/vocab.json"

def generate_test_mel():
    """Exact reproduction of HuggingFace WhisperFeatureExtractor with htk=False, no normalization."""
    sr = 16000
    duration = 30.0
    n_samples = int(sr * duration)
    n_mels = 80
    n_fft = 400
    hop_length = 160

    # 440Hz tone for first 5 seconds
    t = np.arange(n_samples) / sr
    audio = np.zeros(n_samples, dtype=np.float32)
    mask = t < 5.0
    audio[mask] = 0.5 * np.sin(2 * np.pi * 440 * t[mask])

    # Center pad with reflect
    pad_len = n_fft // 2
    audio_padded = np.pad(audio, (pad_len, pad_len), mode='reflect')

    # STFT
    n_frames = 1 + (len(audio_padded) - n_fft) // hop_length
    window = np.hanning(n_fft).astype(np.float32)
    stft = np.zeros((n_frames, n_fft // 2 + 1), dtype=np.complex64)
    for i in range(n_frames):
        start = i * hop_length
        frame = audio_padded[start:start + n_fft].astype(np.float32) * window
        stft[i] = np.fft.rfft(frame)

    magnitude = np.abs(stft) ** 2

    # Mel filterbank: linear scale (htk=False), exactly like HuggingFace
    f_sp = 200.0 / 3.0
    n_freqs = n_fft // 2 + 1  # 201
    low_freq_mel = 0.0
    high_freq_mel = (sr / 2.0) / f_sp  # 120.0
    mel_points = np.linspace(low_freq_mel, high_freq_mel, n_mels + 2)
    hz_points = mel_points * f_sp

    filterbank = np.zeros((n_mels, n_freqs), dtype=np.float32)
    for i in range(n_mels):
        low = hz_points[i]
        center = hz_points[i + 1]
        high = hz_points[i + 2]
        for j in range(n_freqs):
            freq = j * sr / n_fft
            if low <= freq <= center and center > low:
                filterbank[i, j] = (freq - low) / (center - low)
            elif center < freq <= high and high > center:
                filterbank[i, j] = (high - freq) / (high - center)

    mel_energy = magnitude @ filterbank.T
    mel_energy = np.clip(mel_energy, 1e-9, None)
    log_mel = np.log(mel_energy)  # natural log

    mel = log_mel.T  # [n_mels, n_frames]
    if mel.shape[1] > 3000:
        mel = mel[:, :3000]
    elif mel.shape[1] < 3000:
        mel = np.pad(mel, ((0, 0), (0, 3000 - mel.shape[1])), mode='constant')

    return mel.astype(np.float32)

def main():
    mel = generate_test_mel()
    print(f"Mel shape: {mel.shape}, range: [{mel.min():.4f}, {mel.max():.4f}], mean: {mel.mean():.6f}")

    # Load vocab
    with open(VOCAB_PATH, 'r', encoding='utf-8') as f:
        vocab_json = json.load(f)
    vocab = {v: k for k, v in vocab_json.items()}
    print(f"Vocab: {len(vocab)} entries")
    for i in [5342, 918, 50256, 50257, 50258, 50259, 50359, 50363]:
        print(f"  vocab[{i}] = {repr(vocab.get(i, 'MISSING'))}")

    # Encoder
    print(f"\n--- Encoder ---")
    enc = ort.InferenceSession(ENCODER_PATH)
    enc_in = enc.get_inputs()[0]
    enc_out = enc.get_outputs()
    print(f"Input: {enc_in.name} {enc_in.shape}")
    print(f"Outputs: {[(o.name, o.shape) for o in enc_out]}")

    hidden = enc.run(None, {enc_in.name: mel.reshape(1, 80, 3000)})[0]
    print(f"Output shape: {hidden.shape}")
    print(f"Mean: {hidden.mean():.6f}, Std: {hidden.std():.6f}, AbsMax: {np.abs(hidden).max():.6f}")
    print(f"hidden[0,0,:5] = {hidden[0, 0, :5]}")
    print(f"hidden[0,750,:5] = {hidden[0, 750, :5]}")
    print(f"hidden[0,1499,:5] = {hidden[0, 1499, :5]}")

    # Decoder
    print(f"\n--- Decoder ---")
    dec = ort.InferenceSession(DECODER_PATH)
    dec_inputs = dec.get_inputs()
    dec_outputs = dec.get_outputs()
    for inp in dec_inputs:
        print(f"  Input: {inp.name} type={inp.type} shape={inp.shape}")
    for out in dec_outputs:
        print(f"  Output: {out.name} type={out.type} shape={out.shape}")

    # Step 0: use_cache_branch = False
    prompt = np.array([[50258, 50259, 50359, 50363]], dtype=np.int64)
    print(f"\nPrompt: {prompt[0].tolist()}")

    feed = {}
    for inp in dec_inputs:
        n = inp.name
        if n == "input_ids":
            feed[n] = prompt
        elif "encoder_hidden" in n or "encoder_output" in n:
            feed[n] = hidden
        elif "past" in n:
            # empty cache: [1, heads, 0, head_dim]
            shape = []
            for d in inp.shape:
                if d is None or (isinstance(d, str)) or d <= 0:
                    shape.append(1)
                else:
                    shape.append(d)
            shape[2] = 0  # seq_len = 0 for empty
            feed[n] = np.zeros(shape, dtype=np.float32)
        elif "use_cache" in n:
            feed[n] = np.array([False], dtype=np.bool_)

    result = dec.run(None, feed)
    logits = result[0]
    print(f"Step 0 logits shape: {logits.shape}")
    last = logits[0, -1, :]
    top5 = np.argsort(last)[-5:][::-1]
    print("Step 0 top 5:")
    for idx in top5:
        print(f"  token {idx}: {last[idx]:.4f} text={repr(vocab.get(idx, 'MISSING'))}")

    # Greedy decode 20 steps (simple: re-feed all tokens, no KV cache reuse)
    print(f"\n--- Greedy decode (simple, no KV reuse, 20 steps) ---")
    tokens = [50258, 50259, 50359, 50363]
    for step in range(20):
        ids = np.array([tokens], dtype=np.int64)
        feed2 = {}
        for inp in dec_inputs:
            n = inp.name
            if n == "input_ids":
                feed2[n] = ids
            elif "encoder_hidden" in n or "encoder_output" in n:
                feed2[n] = hidden
            elif "past" in n:
                shape = []
                for d in inp.shape:
                    if d is None or (isinstance(d, str)) or d <= 0:
                        shape.append(1)
                    else:
                        shape.append(d)
                shape[2] = 0
                feed2[n] = np.zeros(shape, dtype=np.float32)
            elif "use_cache" in n:
                feed2[n] = np.array([False], dtype=np.bool_)
        res = dec.run(None, feed2)
        last = res[0][0, -1, :]
        tok = int(np.argmax(last))
        tokens.append(tok)
        print(f"  step {step}: token={tok} text={repr(vocab.get(tok, 'MISSING'))} logit={last[tok]:.4f}")
        if tok == 50256:
            break

    decoded = "".join(vocab.get(t, f'[{t}]') for t in tokens if t != 50256)
    print(f"\nDecoded: {repr(decoded)}")

if __name__ == "__main__":
    main()
