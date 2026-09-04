# IOPA-P2-06 production OCR sidecar. Offline build: every artifact is vendored in this context.
# Base: python:3.12-slim-bookworm (glibc 2.36), linux/amd64 only; ARM/Alpine are inadmissible.
FROM python:3.12-slim-bookworm@sha256:356b0d18f9385f4bdcc673af60e1e64c9d1504952e4ec36ee32044c722a6bc4e

COPY vendor/ /opt/vendor/
COPY requirements.lock headless.lock extract_models.py sidecar_server.py probe_client.py /opt/ocr-sidecar-src/

RUN set -eux; \
    pip install --no-cache-dir --no-index --find-links /opt/vendor \
        --require-hashes -r /opt/ocr-sidecar-src/requirements.lock; \
    pip install --no-cache-dir --no-index --no-deps --find-links /opt/vendor \
        --require-hashes -r /opt/ocr-sidecar-src/headless.lock; \
    python /opt/ocr-sidecar-src/extract_models.py /opt/vendor /opt/models; \
    mkdir -p /opt/ocr-sidecar /run/ocr; \
    cp /opt/ocr-sidecar-src/sidecar_server.py /opt/ocr-sidecar/sidecar_server.py; \
    cp /opt/ocr-sidecar-src/probe_client.py /opt/ocr-sidecar/probe_client.py; \
    rm -rf /opt/vendor /opt/ocr-sidecar-src /root/.cache; \
    useradd --system --uid 10001 --home-dir /tmp --shell /usr/sbin/nologin ocrsidecar; \
    chown -R ocrsidecar:ocrsidecar /run/ocr /opt/models /opt/ocr-sidecar; \
    chmod 0550 /opt/ocr-sidecar/sidecar_server.py /opt/ocr-sidecar/probe_client.py; \
    chmod -R a-w /opt/models

USER ocrsidecar
ENV HOME=/tmp \
    PYTHONUTF8=1 \
    PYTHONNOUSERSITE=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    NO_PROXY=* \
    no_proxy=* \
    OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1

# No EXPOSE: the sidecar has no IP networking; HTTP/1.1 over the Unix socket only.
ENTRYPOINT ["python", "/opt/ocr-sidecar/sidecar_server.py", \
            "--socket", "/run/ocr/document-vision.sock", "--model-root", "/opt/models"]
