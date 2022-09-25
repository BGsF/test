import requests


url_test = "https://dart.fss.or.kr/"
#url_test = "http://www.naver.com"
r_test = requests.get(url_test)



print(r_test)
print(r_test.request.headers)