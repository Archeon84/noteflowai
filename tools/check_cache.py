import sys
sys.stdout.reconfigure(encoding='utf-8')
import torch
from transformers import WhisperForConditionalGeneration

model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()

enc_out = torch.randn(1, 1500, 384)

with torch.no_grad():
    out0 = model.get_decoder()(
        input_ids=torch.tensor([[50258, 50259, 50359, 50363]], dtype=torch.long),
        encoder_hidden_states=enc_out,
        use_cache=True,
        past_key_values=None,
        return_dict=True,
    )
    past = out0.past_key_values
    print(f'past type: {type(past).__name__}')

    # Check if it's a DynamicCache or similar
    if hasattr(past, 'key_cache'):
        print(f'key_cache: {len(past.key_cache)} layers')
        for i, k in enumerate(past.key_cache):
            print(f'  self key[{i}]: shape={k.shape}')
        for i, v in enumerate(past.value_cache):
            print(f'  self val[{i}]: shape={v.shape}')
    
    # Check the legacy tuple format
    if hasattr(past, 'to_legacy_cache'):
        legacy = past.to_legacy_cache()
        print(f'Legacy cache length: {len(legacy)}')
        for i, layer in enumerate(legacy):
            print(f'  layer {i}: {len(layer)} tensors')
            for j, kv in enumerate(layer):
                print(f'    tensor {j}: shape={kv.shape}')
    
    # Step 1
    out1 = model.get_decoder()(
        input_ids=torch.tensor([[9279]], dtype=torch.long),
        encoder_hidden_states=enc_out,
        use_cache=True,
        past_key_values=past,
        return_dict=True,
    )
    past1 = out1.past_key_values
    print(f'\nStep 1 past type: {type(past1).__name__}')
    if hasattr(past1, 'key_cache'):
        for i, k in enumerate(past1.key_cache):
            print(f'  self key[{i}]: shape={k.shape}')
    
    # Now check: does PyTorch decoder cache cross-attention KV?
    # In newer transformers, cross-attention KV is NOT cached
    # It is recomputed from encoder_hidden_states at each step
    print(f'\nChecking if cross-attention is cached...')
    # Look at the decoder layer structure
    for name, param in model.get_decoder().named_parameters():
        if 'encoder_attn' in name and ('key' in name or 'value' in name):
            print(f'  {name}: {param.shape}')
            break
