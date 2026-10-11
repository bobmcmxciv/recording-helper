"""Export FunASR SenseVoice-Small to ONNX (fp32) together with tokens.json and am.mvn.

Usage: python export_sensevoice_onnx.py [output_dir]
Then convert with onnx2tf (see docs/MODELS.md):
  onnx2tf -i sv_onnx/model.onnx -o sv_tflite -ois speech:1,167,560 speech_lengths:1 language:1 textnorm:1
"""
import glob
import os
import shutil
import sys

from funasr import AutoModel

out = sys.argv[1] if len(sys.argv) > 1 else "sv_onnx"
model = AutoModel(model="iic/SenseVoiceSmall", device="cpu", disable_update=True)
result = model.export(type="onnx", quantize=False)
src = result if isinstance(result, str) and os.path.isdir(result) else None
if src is None:
    candidates = glob.glob(os.path.expanduser("~/.cache/modelscope/hub/**/SenseVoiceSmall/model.onnx"), recursive=True)
    src = os.path.dirname(candidates[0]) if candidates else None
if src is None:
    raise SystemExit("ONNX export not found; check the funasr export output")
os.makedirs(out, exist_ok=True)
for name in ("model.onnx", "tokens.json", "am.mvn", "config.yaml"):
    path = os.path.join(src, name)
    if os.path.exists(path):
        shutil.copy(path, out)
        print("copied", name)

import onnx  # noqa: E402

graph = onnx.load(os.path.join(out, "model.onnx")).graph
fmt = lambda v: f"{v.name} {[d.dim_param or d.dim_value for d in v.type.tensor_type.shape.dim]}"
print("inputs:", [fmt(v) for v in graph.input])
print("outputs:", [fmt(v) for v in graph.output])
