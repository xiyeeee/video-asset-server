"""从本机验证开发环境的注册、路由、鉴权和依赖健康。"""
import argparse
import base64
import json
import os
import subprocess
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def request(url, *, expected=200, headers=None, form=None, method=None, read_json=True):
    data = urlencode(form).encode() if form is not None else None
    req = Request(url, data=data, headers=headers or {}, method=method)
    try:
        response = urlopen(req, timeout=20)
    except HTTPError as error:
        if error.code != expected:
            raise SystemExit(f"{url} 返回 HTTP {error.code}，预期 {expected}") from None
        response = error
    with response:
        if response.status != expected:
            raise SystemExit(f"{url} 返回 HTTP {response.status}，预期 {expected}")
        return (json.load(response) if read_json else None), response.headers


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def sign_jwt(key, claims):
    def encode(data):
        return base64.urlsafe_b64encode(data).rstrip(b"=")

    header = b'{"alg":"RS256","typ":"JWT"}'
    signing_input = b".".join((encode(header), encode(json.dumps(claims).encode())))
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key],
        input=signing_input, capture_output=True, check=True,
    ).stdout
    return b".".join((signing_input, encode(signature))).decode()


def verify_jwt(key, auth_url):
    claims = {
        "sub": "dev-smoke",
        "iss": os.environ["JWT_ISSUER"],
        "aud": [os.environ["JWT_AUDIENCE"]],
        "exp": int(time.time()) + 300,
    }
    valid = sign_jwt(key, claims)
    def call(token, expected, code):
        payload, headers = request(
            f"{auth_url}/private", expected=expected,
            headers={"Authorization": f"Bearer {token}"},
        )
        require(payload["code"] == code, f"JWT 响应码不符合预期 {code}")
        if expected == 401:
            require(headers.get("WWW-Authenticate") == "Bearer", "JWT 响应应使用通用认证提示")

    call(valid, 404, "NOT_FOUND")
    call("malformed", 401, "UNAUTHENTICATED")
    call(sign_jwt(key, {**claims, "aud": ["invalid"]}), 401, "UNAUTHENTICATED")
    call(sign_jwt(key, {**claims, "iss": "https://example.invalid"}), 401, "UNAUTHENTICATED")
    call(sign_jwt(key, {**claims, "exp": int(time.time()) - 300}), 401, "UNAUTHENTICATED")
    call(sign_jwt(key, {name: value for name, value in claims.items() if name != "exp"}),
         401, "UNAUTHENTICATED")
    token_parts = valid.split(".")
    token_parts[2] = ("A" if token_parts[2][0] != "A" else "B") + token_parts[2][1:]
    call(".".join(token_parts), 401, "UNAUTHENTICATED")
    print("JWT 签名、issuer、audience、过期时间与错误响应通过")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--marker", help="期望从 Nacos 导入的开发探针配置值")
    parser.add_argument("--jwt-private-key", help="可选，仅使用本地开发密钥生成测试 JWT")
    args = parser.parse_args()

    urls = {
        name: f"http://127.0.0.1:{os.getenv(f'{name.upper()}_PORT', port)}"
        for name, port in (("gateway", 8080), ("auth", 8081), ("media", 8082), ("ai", 8083))
    }
    nacos_addr = os.getenv("NACOS_SERVER_ADDR") or f"127.0.0.1:{os.getenv('NACOS_PORT', 8848)}"
    nacos = f"http://{nacos_addr}/nacos"
    group = os.getenv("NACOS_DISCOVERY_GROUP", "VIDEO_ASSET")
    login, _ = request(f"{nacos}/v3/auth/user/login", form={
        "username": os.environ["NACOS_USERNAME"], "password": os.environ["NACOS_PASSWORD"],
    })
    query = urlencode({
        "groupName": group, "namespaceId": os.getenv("NACOS_NAMESPACE") or "public",
        "pageNo": 1, "pageSize": 100,
    })
    registry, _ = request(f"{nacos}/v3/admin/ns/service/list?{query}",
                          headers={"accessToken": login["accessToken"]})
    require(registry["code"] == 0, "Nacos 服务列表查询失败")
    healthy = {item["name"] for item in registry["data"]["pageItems"]
               if item["groupName"] == group and int(item["healthyInstanceCount"]) > 0}
    require({f"video-asset-{name}" for name in urls} <= healthy, "Nacos 缺少健康注册实例")
    print("Nacos 四个服务均有健康实例")

    for name, url in urls.items():
        health, _ = request(f"{url}/actuator/health")
        require(health.get("status") == "UP", f"{name} 的 Actuator 未就绪")
        print(f"{name} Actuator UP")
        if name == "gateway":
            continue

        routed = f"{urls['gateway']}/api/{name}"
        probe, _ = request(f"{routed}/internal/dev/ping")
        require(probe["code"] == "OK" and probe["data"]["service"] == f"video-asset-{name}",
                f"{name} 的网关探针路由错误")
        if args.marker:
            require(probe["data"]["marker"] == args.marker, f"{name} 未加载预期的 Nacos 配置")
        for endpoint in (url, routed):
            response, _ = request(f"{endpoint}/private", expected=401)
            require(response["code"] == "UNAUTHENTICATED", f"{name} 的 401 响应错误")
        print(f"{name} 网关路由与下游鉴权通过")

    origin = os.getenv("GATEWAY_ALLOWED_ORIGINS", "http://localhost:5173").split(",")[0].strip()
    _, cors_headers = request(
        f"{urls['gateway']}/api/media/internal/dev/ping", method="OPTIONS", read_json=False,
        headers={"Origin": origin, "Access-Control-Request-Method": "GET"},
    )
    require(cors_headers.get("Access-Control-Allow-Origin") == origin, "Gateway CORS 响应错误")
    print("Gateway CORS 预检通过")

    if args.jwt_private_key:
        verify_jwt(args.jwt_private_key, urls["auth"])


if __name__ == "__main__":
    main()
