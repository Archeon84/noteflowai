import json
import sys
import numpy as np
import wave

sys.stdout.reconfigure(encoding='utf-8')

# Load vocab
with open('onnx_models/tiny/vocab.json', 'r', encoding='utf-8') as f:
    vocab = json.load(f)

inv = {v: k for k, v in vocab.items()}
print(f'Vocab size: {len(vocab)} entries')

# Check key special tokens
for tok_id in [50256, 50257, 50258, 50259, 50260, 50264, 50266, 50282, 50358, 50359, 50360, 50361, 50362, 50363, 50364, 50365, 9279, 5342, 16867]:
    ch = inv.get(tok_id, 'MISSING')
    print(f'  {tok_id}: {repr(ch)}')

# Show last 20 tokens
last20 = sorted(vocab.items(), key=lambda x: x[1], reverse=True)[:30]
print('\nLast 30 tokens:')
for k, v in last20:
    print(f'  {v}: {repr(k)}')
