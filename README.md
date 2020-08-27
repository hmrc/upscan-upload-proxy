
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

S3 allows a redirect url to be specified for a successful file upload via the optional `success_action_redirect` form field.
 
This service enriches the S3 api to allow a redirect url to be specified for a failed file upload via the optional 
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

#### Error Reporting

If the S3 file upload attempt fails, the way in which the error is reported to the client service depends upon whether
an `error_action_redirect` form field was set.

If set, we will redirect to the specified URL.  Details of the error will be supplied to this URL as query parameters,
with the names `errorCode`, `errorMessage`, `errorResource` and `errorRequestId`.

The query parameter named `key` contains the globally unique file reference that was allocated by the initiate request 
to identify the upload.

```
HTTP Response Code: 303
Header ("Location" -> "https://myservice.com/errorPage?key=11370e18-6e24-453e-b45a-76d3e32ea33d&errorCode=NoSuchKey&errorMessage=The+resource+you+requested+does+not+exist&errorResource=/mybucket/myfoto.jpg&errorRequestId=4442587FB7D0A2F9")
```

If a redirect URL is not set, we will respond with the failure status code.
The details of the error along with the key will be available from the JSON body that has the following structure:

```
{
 "key": "11370e18-6e24-453e-b45a-76d3e32ea33d",
 "errorCode": "NoSuchKey",
 "errorMessage": "The resource you requested does not exist",
 "errorResource": "/mybucket/myfoto.jpg",
 "errorRequestId": "4442587FB7D0A2F9"
}
```

All error fields are optional.

### OPTIONS v1/uploads/{destination}

Expects a `destination` path parameter which should be the S3 bucket name. `destination` must meet aws bucket name standards (lowercase, alphanumeric, dot and dash characters only). 

Endpoint proxies the `OPTIONS` request on to the S3 bucket and returns the response. This is implemented to support CORS requests from the client.
