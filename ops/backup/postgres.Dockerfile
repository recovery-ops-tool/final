# Postgres image extended with the AWS CLI, needed by pg-wal-archive.sh's
# archive_command to ship WAL segments to OCI Object Storage's S3-compatible API.
# Base switched from postgres:16-alpine (used elsewhere in this repo) to the
# Debian-based postgres:16 here specifically because the official AWS CLI package
# needs glibc, which alpine's musl libc doesn't provide.
FROM postgres:16

RUN apt-get update \
    && apt-get install -y --no-install-recommends awscli postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*
