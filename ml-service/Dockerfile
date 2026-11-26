FROM python:3.12-slim

# Small models run fastest single-threaded; scale with uvicorn workers instead of OpenMP threads.
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PIP_DISABLE_PIP_VERSION_CHECK=1     OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
WORKDIR /opt/gridiq
# Apply Debian security updates between base image releases.
RUN apt-get update && apt-get upgrade -y --no-install-recommends && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml .
COPY app ./app
RUN pip install --no-cache-dir . \
    && groupadd --gid 10001 gridiq \
    && useradd --uid 10001 --gid gridiq --no-create-home gridiq \
    && mkdir -p /run/gridiq/ml \
    && chown gridiq:gridiq /run/gridiq/ml
USER gridiq
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
