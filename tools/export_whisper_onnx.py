import sys
import os

sys.stdout.reconfigure(encoding='utf-8')
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from optimum.onnxruntime import ORTModelForSpeechSeq2Seq

# Export whisper-tiny to ONNX
print('Exporting whisper-tiny to ONNX...')
model = ORTModelForSpeechSeq2Seq.from_pretrained(
    'openai/whisper-tiny',
    export=True,
)
print(f'Model exported successfully!')
print(f'Encoder: {model.encoder is not None}')
print(f'Decoder: {model.decoder is not None}')

# Save to a local directory
output_dir = 'onnx_models/whisper_tiny_fixed'
os.makedirs(output_dir, exist_ok=True)
model.save_pretrained(output_dir)
print(f'Saved to {output_dir}')

# List exported files
for f in os.listdir(output_dir):
    size = os.path.getsize(os.path.join(output_dir, f))
    print(f'  {f}: {size/1024/1024:.1f} MB')
