#!/bin/sh
#
# Wrapper around the base image's run-java.sh.
#
# Under the STDIO transport stdout and stderr carry the JSON-RPC protocol itself, so
# console logging has to be off - otherwise the client sees log lines where it expects
# JSON, which looks like a broken server rather than a misconfiguration. Making the
# caller spell out three environment variables to express one intent is exactly how that
# goes wrong, so the two logging settings are derived here from the one that matters.
#
# Anything the caller sets explicitly is left alone, so a deliberate
# `quarkus.log.console.enable=true` still wins (useful when debugging startup).

# Values may arrive either as MicroProfile-style names (QUARKUS_LOG_CONSOLE_ENABLE) or as
# literal property names containing dots, which the shell cannot expand - hence printenv.
getenv() {
    printenv "$1" 2>/dev/null || true
}

stdio="$(getenv 'quarkus.mcp.server.stdio.enabled')"
[ -n "$stdio" ] || stdio="$(getenv QUARKUS_MCP_SERVER_STDIO_ENABLED)"
[ -n "$stdio" ] || stdio="$(getenv MCP_STDIO)"

if [ "$stdio" = "true" ]; then
    # MCP_STDIO is only an alias, so pass the real property on to Quarkus.
    export QUARKUS_MCP_SERVER_STDIO_ENABLED=true

    if [ -z "$(getenv 'quarkus.log.console.enable')$(getenv QUARKUS_LOG_CONSOLE_ENABLE)" ]; then
        export QUARKUS_LOG_CONSOLE_ENABLE=false
    fi
    if [ -z "$(getenv 'quarkus.log.console.stderr')$(getenv QUARKUS_LOG_CONSOLE_STDERR)" ]; then
        export QUARKUS_LOG_CONSOLE_STDERR=false
    fi
fi

exec /opt/jboss/container/java/run/run-java.sh "$@"
