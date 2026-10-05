import sys
import os
import numpy as np

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

import torch
from transformers import WhisperForConditionalGeneration

print('Loading whisper-tiny...')
model = WhisperForConditionalGeneration.from_pretrained('openai/whisper-tiny')
model.eval()

encoder = model.get_encoder()
decoder = model.get_decoder()
proj_out = model.proj_out

output_dir = 'onnx_models/whisper_tiny_fixed'
os.makedirs(output_dir, exist_ok=True)

# === Export Encoder ===
print('\n=== Exporting Encoder ===')
enc_dummy = torch.randn(1, 80, 3000)
torch.onnx.export(
    encoder,
    (enc_dummy,),
    os.path.join(output_dir, 'encoder_model.onnx'),
    input_names=['input_features'],
    output_names=['last_hidden_state'],
    dynamic_axes={'input_ids': {0: 'batch', 2: 'seq_len'}, 'input_features': {0: 'batch', 2: 'seq_len'}, 'last_hidden_state': {0: 'batch', 1: 'enc_seq'}},
    opset_version=17,
    dynamo=False,
)
print('Encoder exported!')
