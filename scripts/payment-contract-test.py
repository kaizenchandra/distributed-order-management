#!/usr/bin/env python3
"""Black-box async payment API, validation, idempotency and timeout contracts."""
import json
import urllib.request
import uuid

from smoke import token, request, eventually, env

if __name__ == '__main__':
    internal = token();
    customer = token('customer');
    bad = str(uuid.uuid4())
    body = {'orderId': bad, 'customerId': 'contract-customer', 'amount': 1.25, 'currency': 'USD'}
    assert request(8093, '/api/v1/payments/authorize', customer, body, str(uuid.uuid4()))[0] == 403
    assert request(8093, '/api/v1/payments/authorize', internal, body, str(uuid.uuid4()))[0] == 409
    assert request(8093, '/api/v1/admin/payments/' + bad + '/operations', internal)[1] == []
    print('PASS unauthorized and fractional-money requests cannot queue financial work', flush=True)
    payment = str(uuid.uuid4());
    body.update(orderId=payment, amount=100)
    code, ack = request(8093, '/api/v1/payments/authorize', internal, body, str(uuid.uuid4()));
    assert code == 202, (code, ack)


    def completed(op):
        code, result = request(8093, '/api/v1/payments/operations/' + op, internal)
        return code == 200 and isinstance(result, dict) and result.get('status') == 'SUCCEEDED'


    eventually(lambda: completed(ack['operationId']))
    key = str(uuid.uuid4());
    capture = {'paymentScenario': 'capture-timeout'}
    code, ack = request(8093, '/api/v1/payments/' + payment + '/capture', internal, capture, key);
    assert code == 202, (code, ack)
    eventually(lambda: completed(ack['operationId']))
    code, replay = request(8093, '/api/v1/payments/' + payment + '/capture', internal, capture, key);
    assert code == 202 and replay['operationId'] == ack['operationId']
    assert request(8093, '/api/v1/payments/' + payment + '/capture', internal, {}, key)[0] == 409
    assert request(8093, '/api/v1/payments/' + payment + '/refunds', internal, {'refundAmount': -1}, str(uuid.uuid4()))[
               0] == 409
    print('PASS 202 operation polling, stable-key replay and changed-payload rejection', flush=True)
    code, refund = request(8093, '/api/v1/payments/' + payment + '/refunds', internal,
                           {'refundAmount': 100, 'paymentScenario': 'refund-timeout'}, str(uuid.uuid4()));
    assert code == 202, (code, refund)
    eventually(lambda: completed(refund['operationId']))
    local = request(8093, '/api/v1/payments/' + payment, internal)[1]
    assert local['captured'] == 100 and local['refunded'] == 100, local
    req = urllib.request.Request('http://localhost:8100/internal/payments/' + payment,
                                 headers={'X-Provider-Key': env['PROVIDER_KEY']})
    with urllib.request.urlopen(req, timeout=5) as response: remote = json.load(response)
    assert remote['captured'] == 100 and remote['refunded'] == 100 and remote['operation_count'] == 3, remote
    print('PASS capture/refund timeout recovery preserves one provider operation per logical request', flush=True)
