# Test-deployment lane recycler: serves POST /lanes/{lane}/reset by running the repository's recycle script
# against the container runtime. It holds the runtime socket, so it exists only in the workbench E2E stack.
FROM docker:27-cli
RUN apk add --no-cache bash socat util-linux
COPY handler.sh /usr/local/bin/lane-recycler-handler
RUN chmod +x /usr/local/bin/lane-recycler-handler
EXPOSE 8080
ENTRYPOINT ["socat", "TCP-LISTEN:8080,fork,reuseaddr", "EXEC:/usr/local/bin/lane-recycler-handler"]
