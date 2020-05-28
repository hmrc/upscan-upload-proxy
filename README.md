
# upscan-upload-proxy

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").

### Purpose

Enrich the S3 api with the ability to redirect on failure. S3 can only redirect on successful file uploads.

### POST v1/uploads/{destination}

Expects a `destination` path parameter which should be the S3 bucket name where we want to
upload the file. `destination` must meet aws bucket name standards (lowercase, alphanumeric, dot and dash characters only). 

#### Headers

```$xslt
Content-Type	multipart/form-data
Content-Length	xxx
```

#### Body
```$xslt
error_action_redirect	https://www...
``` 

S3 allows a redirect url to be specified for successful file upload via a `success_action_redirect` form field.
 
This service enriches the S3 api to allow a redirect url to be specified for a failed file upload via a 
`error_action_redirect` form field.


#### Example request

```$xslt
POST v1/uploads/{bucket-name} HTTP/1.1
Host: upscan-upload-proxy-development-156735432.eu-west-2.elb.amazonaws.com
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW
Accept: */*
Cache-Control: no-cache
Host: xxx.execute-api.eu-west-2.amazonaws.com
accept-encoding: gzip, deflate
content-length: 1065
Connection: keep-alive
cache-control: no-cache


Content-Disposition: form-data; name="acl"

public-read-write
------WebKitFormBoundary7MA4YWxkTrZu0gW--,
Content-Disposition: form-data; name="acl"

public-read-write
------WebKitFormBoundary7MA4YWxkTrZu0gW--
Content-Disposition: form-data; name="Content-Type"

application/text
------WebKitFormBoundary7MA4YWxkTrZu0gW--,
Content-Disposition: form-data; name="acl"

public-read-write
------WebKitFormBoundary7MA4YWxkTrZu0gW--
Content-Disposition: form-data; name="Content-Type"

application/text
------WebKitFormBoundary7MA4YWxkTrZu0gW--
Content-Disposition: form-data; name="key"

b198de49-e7b5-49a8-83ff-068fc9357481
------WebKitFormBoundary7MA4YWxkTrZu0gW--,
Content-Disposition: form-data; name="acl"

public-read-write
------WebKitFormBoundary7MA4YWxkTrZu0gW--
Content-Disposition: form-data; name="Content-Type"

application/text
------WebKitFormBoundary7MA4YWxkTrZu0gW--
Content-Disposition: form-data; name="key"

b198de49-e7b5-49a8-83ff-068fc9357481
------WebKitFormBoundary7MA4YWxkTrZu0gW--
Content-Disposition: form-data; name="file"; filename="/HelloWorld.txt


------WebKitFormBoundary7MA4YWxkTrZu0gW--
```

```$xslt
curl -X POST \
  https://xxx.execute-api.eu-west-2.amazonaws.com/uploads/{bucket-name} \
  -H 'Accept: */*' \
  -H 'Cache-Control: no-cache' \
  -H 'Connection: keep-alive' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'Host: ik3qo4pzt9.execute-api.eu-west-2.amazonaws.com' \
  -H 'User-Agent: PostmanRuntime/7.11.0' \
  -H 'accept-encoding: gzip, deflate' \
  -H 'cache-control: no-cache' \
  -H 'content-length: 1065' \
  -H 'content-type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW' \
  -F acl=public-read-write \
  -F Content-Type=application/text \
  -F key=b198de49-e7b5-49a8-83ff-068fc9357481 \
  -F file=@HelloWorld.txt
```

### OPTIONS v1/uploads/{destination}

Expects a `destination` path parameter which should be the S3 bucket name. `destination` must meet aws bucket name standards (lowercase, alphanumeric, dot and dash characters only). 

Endpoint proxies the `OPTIONS` request on to the S3 bucket and returns the response. This is implemented to support CORS requests from the client.

  