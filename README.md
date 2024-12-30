# standalone-nuvotifier
Standalone NuVotifier server implementation. From the original README:
> NuVotifier is a secure alternative to using the original Votifier project. 
> NuVotifier will work in place of Votifier - any vote listener that supports 
> Votifier will also support NuVotifier.

## Useful resources
- [Setup Guide](https://github.com/NuVotifier/NuVotifier/wiki/Setup-Guide)
- [Troubleshooting Guide](https://github.com/NuVotifier/NuVotifier/wiki/Troubleshooting-Guide)
- [Developer Information](https://github.com/NuVotifier/NuVotifier/wiki/Developer-Documentation)

## Running
Grab a compiled binary from [GitHub releases](https://github.com/azujelly/standalone-nuvotifier/releases) and run it from the command line. For example:
```shell
$ curl -O https://github.com/azujelly/standalone-nuvotifier/releases/download/3.1.0/nuvotifier-standalone.jar
$ java -Xms512M -Xmx512M -jar nuvotifier-standalone.jar
```

You can also use command line arguments to configure some settings:
```shell
$ java -Xms512M -Xmx512M -jar nuvotifier-standalone.jar --host 127.0.0.1 --config /etc/nuvotifier/
```

To get a full list of options, run:
```shell
$ java -jar nuvotifier-standalone.jar --help
```

## Using Docker
A Docker image is available at [Docker Hub](https://hub.docker.com/r/azurejelly/standalone-nuvotifier). To pull it, run:
```shell
$ docker pull azurejelly/standalone-nuvotifier:latest
```

### Running
You can run the image using a command like:
```shell
$ docker run -p 8192:8192 \
    -v /etc/nuvotifier:/app/config \
    --restart unless-stopped \
    --name nuvotifier \
    azurejelly/standalone-nuvotifier:latest
    --port 8192
```

This will:
- Expose port 8192 on the host machine;
- Map `/etc/nuvotifier` (host) to `/app/config` (container) using bind mounts;
- Restart the container automatically unless stopped;
- Name the container `nuvotifier`;
- Use the `azurejelly/standalone-nuvotifier:latest` image;
- And pass `--port 8192` as a command line argument to NuVotifier.

Additionally, if you're also running your Minecraft server with Docker, you could create a network for cross-container communication:
```shell
$ docker network create votifierNetwork
$ docker run ... --network votifierNetwork ...
```

Note that you will need to recreate your existing containers to do this.

### Compose
The Docker Compose equivalent of the previous `docker run` command (minus networking stuff) is as follows:
```yaml
services:
  forwarder:
    container_name: nuvotifier
    image: azurejelly/standalone-nuvotifier:latest
    restart: unless-stopped
    ports:
      - 8192:8192
    volumes:
      - ./config:/app/config
```

# License

NuVotifier is GNU GPLv3 licensed. This project's license can be viewed [here](LICENSE).
