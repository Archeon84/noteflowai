import onnx
from onnx import helper, TensorProto
import os
import shutil

local_dir = r'C:\Users\alya1\AndroidStudioProjects\NoteFlowAI\app\src\main\cpp\test_models'

for name in ['encoder.onnx', 'decoder.onnx']:
    path = os.path.join(local_dir, name)
    backup = path + '.bak'
    if not os.path.exists(backup):
        shutil.copy2(path, backup)
    
    model = onnx.load(backup)
    
    for node in model.graph.node:
        if node.op_type == 'EPContext':
            for attr in node.attribute:
                if attr.name == 'source':
                    old_source = attr.s
                    attr.s = b'QNNExecutionProvider'
                    print(f'{name}: Changed source from {old_source} to QNNExecutionProvider')
    
    onnx.save(model, path)
    print(f'{name}: Saved ({os.path.getsize(path)} bytes)')

print('Done!')
