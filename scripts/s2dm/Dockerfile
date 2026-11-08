# Throwaway image for COVESA s2dm, used only by export-trailer.sh.
# The build context is a checkout of COVESA/s2dm at a pinned commit. Dependencies come from s2dm's
# own uv.lock (--frozen): a plain `pip install` resolves graphql-core 3.3.x, and s2dm 0.33.0 then
# crashes with "'NoneType' object is not iterable" when it merges schema files or exports VSPEC.
FROM python:3.12-slim
RUN pip install --no-cache-dir uv
COPY . /opt/s2dm
WORKDIR /opt/s2dm
RUN UV_PROJECT_ENVIRONMENT=/opt/venv uv sync --frozen --no-dev --no-cache
ENV PATH=/opt/venv/bin:$PATH HOME=/tmp
WORKDIR /work
ENTRYPOINT ["s2dm"]
