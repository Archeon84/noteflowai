import onnx
import os

local_dir = r'C:\Users\alya1\AndroidStudioProjects\NoteFlowAI\app\src\main\cpp\test_models'
for name in ['encoder.onnx', 'decoder.onnx']:
    path = os.path.join(local_dir, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        model = onnx.load(path)
        print(f'=== {name} (IR version: {model.ir_version}, opset: {[o.version for o in model.opset_import]}) ===')
        for node in model.graph.node:
            print(f'  Node: {node.name} op={node.op_type}')
            attrs = {a.name: a for a in node.attribute}
            if 'source' in attrs:
                print(f'    source: {attrs["source"].s}')
            if 'embed_mode' in attrs:
                print(f'    embed_mode: {attrs["embed_mode"].i}')
            if 'ep_cache_context' in attrs:
                ctx = attrs['ep_cache_context'].s
                if len(ctx) > 80:
                    print(f'    ep_cache_context: {ctx[:80]}...')
                else:
                    print(f'    ep_cache_context: {ctx}')
            for inp in node.input:
                print(f'    input: {inp}')
            for out in node.output:
                print(f'    output: {out}')
    else:
        sz = os.path.getsize(path) if os.path.exists(path) else 'missing'
        print(f'{name}: not found or empty ({sz})')
