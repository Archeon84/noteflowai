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

# Test 1: Default generate (should be English with v5.13)
with torch.no_grad():
    out = model.generate(inputs.input_features, language='en', task='transcribe', max_length=448)
    text = processor.batch_decode(out, skip_special_tokens=True)[0]
    print(f'Default (en, transcribe): {repr(text)}')

# Test 2: With temperature=0 (greedy)
with torch.no_grad():
    out = model.generate(inputs.input_features, language='en', task='transcribe', max_length=448, do_sample=False)
    text = processor.batch_decode(out, skip_special_tokens=True)[0]
    print(f'Greedy: {repr(text)}')

# Test 3: With temperature=0.8
with torch.no_grad():
    out = model.generate(inputs.input_features, language='en', task='transcribe', max_length=448, do_sample=True, temperature=0.8)
    text = processor.batch_decode(out, skip_special_tokens=True)[0]
    print(f'Temp=0.8: {repr(text)}')

# Test 4: Manual decode with logits processors
print('\n--- Manual decode with logits processors ---')
with torch.no_grad():
    enc_out = model.get_encoder()(inputs.input_features).last_hidden_state
    
    from transformers import LogitsProcessorList
    logits_processor = model._get_logits_processor(
        generation_config=model.generation_config,
        input_ids=torch.tensor([[50258, 50259, 50359, 50363]], dtype=torch.long),
        logits_processor=LogitsProcessorList(),
    )
    print(f'Number of logits processors: {len(logits_processor)}')
    for i, proc in enumerate(logits_processor):
        print(f'  [{i}] {type(proc).__name__}')

    prompt = [50258, 50259, 50359, 50363]
    tokens = list(prompt)
    past_kv = None
    
    for step in range(50):
        ids = torch.tensor([[tokens[-1]] if step > 0 else tokens], dtype=torch.long)
        
        out_dec = model.get_decoder()(
            input_ids=ids,
            encoder_hidden_states=enc_out,
            use_cache=(step > 0),
            past_key_values=past_kv,
            return_dict=True,
        )
        past_kv = out_dec.past_key_values
        logits = model.proj_out(out_dec.last_hidden_state)
        
        # Apply logits processor
        processed_logits = logits_processor(torch.tensor([tokens]), logits)
        next_logits = processed_logits[0, -1, :]
        next_token = int(torch.argmax(next_logits))
        tokens.append(next_token)
        
        if step < 30 or next_token == 50256:
            text = processor.tokenizer.decode([next_token])
            print(f'  step {step}: id={next_token} text={repr(text)}')
        
        if next_token == 50256:
            break
    
    result = processor.tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
    print(f'\nManual with processors: {repr(result)}')

# Test 5: Manual decode WITHOUT logits processors (greedy)
print('\n--- Manual decode WITHOUT logits processors ---')
with torch.no_grad():
    enc_out = model.get_encoder()(inputs.input_features).last_hidden_state
    
    tokens = list(prompt)
    past_kv = None
    
    for step in range(20):
        ids = torch.tensor([[tokens[-1]] if step > 0 else tokens], dtype=torch.long)
        
        out_dec = model.get_decoder()(
            input_ids=ids,
            encoder_hidden_states=enc_out,
            use_cache=(step > 0),
            past_key_values=past_kv,
            return_dict=True,
        )
        past_kv = out_dec.past_key_values
        logits = model.proj_out(out_dec.last_hidden_state)
        
        next_logits = logits[0, -1, :]
        next_token = int(torch.argmax(next_logits))
        tokens.append(next_token)
        
        text = processor.tokenizer.decode([next_token])
        print(f'  step {step}: id={next_token} text={repr(text)}')
    
    result = processor.tokenizer.decode(tokens[len(prompt):], skip_special_tokens=True)
    print(f'\nManual without processors: {repr(result)}')
