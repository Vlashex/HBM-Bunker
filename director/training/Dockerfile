FROM python:3.12-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258
ENV PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1 HF_HOME=/cache/huggingface HF_HUB_DISABLE_TELEMETRY=1 DO_NOT_TRACK=1 TOKENIZERS_PARALLELISM=false OMP_NUM_THREADS=6
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates git build-essential cmake && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir torch==2.10.0 torchvision==0.25.0 --index-url https://download.pytorch.org/whl/cu128
RUN pip install --no-cache-dir transformers==5.20.1 peft==0.21.2 accelerate==1.15.0 bitsandbytes==0.50.2 safetensors==0.8.0 pillow
WORKDIR /app
CMD ["python", "-m", "training.train", "--help"]
