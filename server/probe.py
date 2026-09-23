import json, urllib.request, time
url="https://i.ilife798.com/api/v1/acc/score/mission-lst"
req=urllib.request.Request(url, headers={
 "ApplicationType":"1,5","VersionCode":"3.1.4",
 "user-agent":"Android_ilife798_3.1.4","Authorization":"dummy.token.value","Content-Type":"application/json; charset=UTF-8"})
try:
    with urllib.request.urlopen(req, timeout=20) as r:
        print("HTTP", r.status); print(r.read().decode("utf-8","replace")[:800])
except urllib.error.HTTPError as e:
    print("HTTPErr", e.code); print(e.read().decode("utf-8","replace")[:800])
except Exception as e:
    print("ERR", type(e).__name__, e)
print("local_ms =", int(time.time()*1000))
