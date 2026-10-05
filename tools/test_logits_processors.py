import sys, os, numpy as np, wave
sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
from transformers import WhisperForConditionalGeneration, WhisperProcessor

model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()
processor = WhisperProcessor.from_pretrained('openai/whisper-tiny')

with wave.open('test_audio.wav', 'rb') as wf:
    sr = wf.getframerate()
    raw = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    max_amp = np.max(np.abs(samples))
    scale = (0.7 * 32768 / max_amp) if max_amp < 16384 else 1.0
    pcmf = np.clip(samples / 32768.0 * scale, -1.0, 1.0)

inputs = processor(pcmf, sampling_rate=16000, return_tensors='pt')

# Test 1: model.generate() with forced decoder ids (this is what HuggingFace does internally)
with torch.no_grad():
    forced_ids = processor.get_decoder_prompt_ids(language='en', task='transcribe')
    print(f'Forced decoder IDs: {forced_ids}')
    
    # Full generate (with all logits processors - this is what works)
    out_full = model.generate(inputs.input_features, max_length=448)
    text_full = processor.batch_decode(out_full, skip_special_tokens=True)[0]
    print(f'Full generate: {repr(text_full)}')
    
    # Greedy generate (no sampling, but WITH logits processors - model.generate uses them by default)
    out_greedy = model.generate(inputs.input_features, max_length=448, do_sample=False)
    text_greedy = processor.batch_decode(out_greedy, skip_special_tokens=True)[0]
    print(f'Greedy generate: {repr(text_greedy)}')

# Test 2: Manual decode WITHOUT logits processors (this is what ONNX does)
print('\n--- Manual decode WITHOUT logits processors ---')
with torch.no_grad():
    enc_out = model.get_encoder()(inputs.input_features).last_hidden_state
    
    sot = processor.tokenizer.convert_tokens_to_ids('<|startoftranscript|>')
    lang_en = processor.tokenizer.convert_tokens_to_ids('<|en|>')
    task_transcribe = processor.tokenizer.convert_tokens_to_ids('<|transcribe|>')
    notimestamps = processor.tokenizer.convert_tokens_to_ids('<|notimestamps|>')
    prompt = [sot, lang_en, task_transcribe, notimestamps]
    
    tokens = list(prompt)
    for step in range(20):
        ids = torch.tensor([[tokens[-1]] if step > 0 else tokens], dtype=torch.long)
        
        out = model.get_decoder()(
            input_ids=ids,
            encoder_hidden_states=enc_out,
            use_cache=(step > 0),
            past_key_values=None if step == 0 else past_kv,
            return_dict=True,
        )
        past_kv = out.past_key_values
        logits = model.proj_out(out.last_hidden_state)
        
        next_logits = logits[0, -1, :]
        next_token = int(torch.argmax(next_logits))
        tokens.append(next_token)
        print(f'  step {step}: id={next_token} text={repr(processor.tokenizer.decode([next_token]))} logit={next_logits[next_token]:.4f}')

# Test 3: What logits processors does generate use?
print('\n--- Checking what logits processors generate() uses ---')
from transformers import LogitsProcessorList, SuppressTokensLogitsProcessor, SuppressTokensAtBeginLogitsProcessor

logits_processor = model._get_logits_processor(
    generation_config=model.generation_config,
    input_ids=forced_ids,
    encoder_outputs=None,
    prefix_allowed_tokens_fn=None,
    logits_processor=LogitsProcessorList(),
)

print(f'Number of logits processors: {len(logits_processor)}')
for i, proc in enumerate(logits_processor):
    print(f'  [{i}] {type(proc).__name__}')

# Test 4: Run WITH logits processor manually
print('\n--- Manual decode WITH logits processor ---')
with torch.no_grad():
    enc_out = model.get_encoder()(inputs.input_features).last_hidden_state
    
    tokens = list(prompt)
    past_kv = None
    for step in range(30):
        ids = torch.tensor([[tokens[-1]] if step > 0 else tokens], dtype=torch.long)
        
        out = model.get_decoder()(
            input_ids=ids,
            encoder_hidden_states=enc_out,
            use_cache=(step > 0),
            past_key_values=past_kv if step > 0 else None,
            return_dict=True,
        )
        past_kv = out.past_key_values
        logits = model.proj_out(out.last_hidden_state)
        
        # Apply logits processor
        next_logits = logits_processor(torch.tensor([tokens]), logits)[0, -1, :]
        next_token = int(torch.argmax(next_logits))
        tokens.append(next_token)
        text = processor.tokenizer.decode([next_token])
        print(f'  step {step}: id={next_token} text={repr(text)} logit={next_logits[next_token]:.4f}')
        if next_token == processor.tokenizer.eos_token_id:
            break
    
    text = processor.tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
    print(f'\nResult: {repr(text)}')
