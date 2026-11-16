import http from 'k6/http';
import {check} from 'k6';

export const options = {
    scenarios: {
        checkout: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 100),
            timeUnit: '1s',
            duration: __ENV.DURATION || '60s',
            preAllocatedVUs: 50,
            maxVUs: 2000
        }
    }, thresholds: {http_req_failed: ['rate<0.01']}
};
export default function () {
    const r = http.post(`${__ENV.BASE_URL || 'http://localhost:8099'}/api/v1/orders`, JSON.stringify({
        items: [{
            sku: 'SKU-1',
            quantity: 1
        }]
    }), {
        headers: {
            Authorization: `Bearer ${__ENV.TOKEN}`,
            'Content-Type': 'application/json',
            'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`
        }
    });
    check(r, {'accepted': r.status === 202});
}
