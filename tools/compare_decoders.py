import sys, os, numpy as np, wave
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
import onnxruntime as ort
from transformers import WhisperForConditionalGeneration, WhisperTokenizer, WhisperFeatureExtractor

model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()
tokenizer = WhisperTokenizer.from_pretrained('openai/whisper-tiny')
feat_extractor = WhisperFeatureExtractor.from_pretrained('openai/whisper-tiny')

with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

inputs = feat_extractor(pcmf.tolist(), sampling_rate=16000, return_tensors='pt', return_attention_mask=False)
input_features = inputs.input_features

# Run PyTorch encoder
with torch.no_grad():
    enc_out_pt = model.get_encoder()(input_features).last_hidden_state
print(f'PT encoder: shape={enc_out_pt.shape}, mean={enc_out_pt.mean():.6f}')

# Run PyTorch decoder step 0
sot = tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
lang_en = tokenizer.convert_tokens_to_ids('<|en|>')
task_transcribe = tokenizer.convert_tokens_to_ids('<|transcribe|>')
notimestamps = tokenizer.convert_tokens_to_ids('<|notimestamps|>')
prompt = [sot, lang_en, task_transcribe, notimestamps]

input_ids = torch.tensor([prompt], dtype=torch.long)

with torch.no_grad():
    dec_out = model.get_decoder()(
        input_ids=input_ids,
        encoder_hidden_states=enc_out_pt,
        use_cache=False,
        return_dict=True,
    )
    logits_pt = model.proj_out(dec_out.last_hidden_state)
    print(f'PT step 0 logits shape: {logits_pt.shape}')
    top5 = torch.argsort(logits_pt[0, -1, :], descending=True)[:5]
    for t in top5:
        print(f'  PT: id={t.item()} text={repr(tokenizer.decode([t.item()]))} logit={logits_pt[0,-1,t].item():.4f}')

# Now run ONNX decoder with same encoder output
dec_onnx = ort.InferenceSession('onnx_models/tiny/onnx/decoder_model_merged.onnx')
dec_inputs = {inp.name: inp for inp in dec_onnx.get_inputs()}

feed = {}
for inp_name, inp_obj in dec_inputs.items():
    if inp_name == 'input_ids':
        feed[inp_name] = np.array([prompt], dtype=np.int64)
    elif inp_name == 'encoder_hidden_states':
        feed[inp_name] = enc_out_pt.numpy().astype(np.float32)
    elif inp_name == 'use_cache_branch':
        feed[inp_name] = np.array([False], dtype=np.bool_)
    else:
        feed[inp_name] = np.zeros([1, 6, 0, 64], dtype=np.float32)

result = dec_onnx.run(None, feed)
onnx_logits = result[0]
print(f'\nONNX step 0 logits shape: {onnx_logits.shape}')
top5_onnx = np.argsort(onnx_logits[0, -1, :])[::-1][:5]
for t in top5_onnx:
    print(f'  ONNX: id={t} text={repr(tokenizer.decode([t]))} logit={onnx_logits[0,-1,t]:.4f}')

# Compare logits directly
diff = np.abs(logits_pt.numpy() - onnx_logits)
print(f'\nLogits diff: max={diff.max():.6f}, mean={diff.mean():.6f}')

if diff.max() > 0.01:
    print('WARNING: ONNX decoder logits differ significantly from PyTorch!')
    # Show top 20 from both
    pt_top20 = torch.argsort(logits_pt[0, -1, :], descending=True)[:20]
    onnx_top20 = np.argsort(onnx_logits[0, -1, :])[::-1][:20]
    print(f'PT  top20 ids: {pt_top20.tolist()}')
    print(f'ONNX top20 ids: {onnx_top20.tolist()}')
else:
    print('ONNX decoder logits match PyTorch!')
