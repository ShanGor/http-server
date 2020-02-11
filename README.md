# Static Http Server

A simple HTTP server implementation with Kotlin and Netty, mainly to serve static contents like downloading, usage:
````
java -D"http.home=/usr/local/var/www" -jar /tmp/http-server.jar

# like
nohup java -Dhttp.home=/home/`whoami`/downloads -jar http-server.jar &

````