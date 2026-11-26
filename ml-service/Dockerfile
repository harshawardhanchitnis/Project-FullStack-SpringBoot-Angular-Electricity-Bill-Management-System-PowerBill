FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /opt/gridiq
COPY pyproject.toml .
COPY app ./app
RUN pip install --no-cache-dir . \
    && groupadd --gid 10001 gridiq \
    && useradd --uid 10001 --gid gridiq --no-create-home gridiq
USER gridiq
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
