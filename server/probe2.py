import json, urllib.request, time
paths=["/api/v1/acc/","/api/v1/ui/app/master","/api/v1/ui/app/dev/status?did=x&more=false",
       "/api/v1/acc/score/score-lst?page=0&size=5&hasCount=true","/api/v1/ui/app/acc-pgas",
       "/api/v1/acc/view-info"]
for p in paths:
    url="https://i.ilife798.com"+p
    req=urllib.request.Request(url, headers={
     "ApplicationType":"1,5","VersionCode":"3.1.4",
     "user-agent":"Android_ilife798_3.1.4","Authorization":"dummy.token.value"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            print(p, "->", r.read().decode("utf-8","replace")[:400])
    except Exception as e:
        print(p, "ERR", type(e).__name__, str(e)[:200])
    print()
