#!/bin/sh
set -e

echo "============================================"
echo "  AI Code Review Assistant"
echo "============================================"
echo "Repository: ${GITHUB_REPOSITORY}"
echo "Event:      ${GITHUB_EVENT_NAME}"
echo "SHA:        ${GITHUB_SHA}"
echo "============================================"

# If invoked with custom command like "java -version", execute it directly
if [ "$1" = "java" ] || [ "$1" = "--version" ] || [ "$1" = "-version" ]; then
    exec "$@"
fi

# Validate we're running in a GitHub Actions context
if [ -z "$GITHUB_TOKEN" ] && [ -n "$INPUT_GITHUB_TOKEN" ]; then
    export GITHUB_TOKEN="$INPUT_GITHUB_TOKEN"
fi

if [ -z "$GITHUB_TOKEN" ]; then
    echo "::error::GITHUB_TOKEN is not set. Please provide 'github_token' or pass GITHUB_TOKEN env."
    exit 1
fi

if [ -z "$GITHUB_EVENT_PATH" ]; then
    echo "::error::GITHUB_EVENT_PATH is not set. This action must be triggered by a pull_request event."
    exit 1
fi

# Run the Java application
# All configuration comes from environment variables set by GitHub Actions
exec java \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Xmx512m \
    -jar /app/app.jar "$@"
