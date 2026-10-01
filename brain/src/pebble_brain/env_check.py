"""M0 check: is the training machine ready?

Run:  uv run python -m pebble_brain.env_check

Prints library versions and the GPU, then trains a tiny model on the GPU (and on the CPU
for comparison). "Done" for M0 means this ends with `READY`.
"""

import platform
import time

import torch
from torch import nn


def tiny_training_run(device: str, steps: int = 300) -> float:
    """Fits y = sin(x) with a small MLP; returns seconds taken. Proves forward/backward/step work."""
    torch.manual_seed(0)
    x = torch.linspace(-3, 3, 4096, device=device).unsqueeze(1)
    y = torch.sin(x)
    model = nn.Sequential(nn.Linear(1, 128), nn.ReLU(), nn.Linear(128, 128), nn.ReLU(), nn.Linear(128, 1)).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-2)
    start = time.perf_counter()
    for _ in range(steps):
        opt.zero_grad()
        loss = nn.functional.mse_loss(model(x), y)
        loss.backward()
        opt.step()
    if device == "cuda":
        torch.cuda.synchronize()
    took = time.perf_counter() - start
    print(f"  {device:>4}: final loss {loss.item():.5f} in {took:.2f}s")
    assert loss.item() < 0.01, "model did not learn — something is wrong with the install"
    return took


def main() -> None:
    print(f"Python      {platform.python_version()}")
    print(f"PyTorch     {torch.__version__}")
    for lib in ("transformers", "sentence_transformers", "datasets", "onnxruntime"):
        try:
            mod = __import__(lib)
            print(f"{lib:<12}{getattr(mod, '__version__', '?')}")
        except ImportError:
            print(f"{lib:<12}MISSING")

    cuda = torch.cuda.is_available()
    print(f"CUDA        {'yes' if cuda else 'NO'}")
    if cuda:
        p = torch.cuda.get_device_properties(0)
        print(f"GPU         {p.name}, {p.total_memory / 2**30:.1f} GB VRAM, compute {p.major}.{p.minor}")

    print("Tiny training run (fit sin(x)):")
    tiny_training_run("cpu")
    if cuda:
        tiny_training_run("cuda")
    print("READY" if cuda else "CPU-only: training works, but install a CUDA build of torch for the RTX 4050.")


if __name__ == "__main__":
    main()
