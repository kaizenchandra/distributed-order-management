import http from 'k6/http';
import {sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';

const ack = new Trend('oms_command_ms', true), saga = new Trend('oms_saga_ms', true);
const offered = new Counter('oms_offered'), accepted = new Counter('oms_accepted'),
    completed = new Counter('oms_completed'), failed = new Counter('oms_failed'),
    timeouts = new Counter('oms_timeouts'), pollErrors = new Counter('oms_poll_errors'),
    pendingPolls = new Counter('oms_pending_polls');
export const options = {
    scenarios: {
        checkout: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 10),
            timeUnit: '1s',
            duration: __ENV.DURATION || '20s',
            preAllocatedVUs: Number(__ENV.VUS || 300),
            maxVUs: Number(__ENV.MAX_VUS || 500),
            gracefulStop: __ENV.GRACE || '120s'
        }
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: {oms_failed: ['count==0'], oms_timeouts: ['count==0'], dropped_iterations: ['count==0']}
};
export default function () {
    const started = Date.now();
    offered.add(1);
    const headers = {
        Authorization: `Bearer ${__ENV.CUSTOMER_TOKEN}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `${__ENV.RUN_ID}-${__VU}-${__ITER}`
    };
    const response = http.post(`${__ENV.ORDER_URL || 'http://localhost:8091'}/api/v1/orders`, JSON.stringify({
        items: [{
            sku: 'SKU-1',
            quantity: 1
        }]
    }), {headers, tags: {operation: 'checkout'}, timeout: '10s'});
    ack.add(response.timings.duration);
    if (response.status !== 202) {
        failed.add(1);
        return;
    }
    accepted.add(1);
    const id = response.json('orderId');
    sleep(1);
    while (Date.now() - started < Number(__ENV.SAGA_WAIT_MS || 110000)) {
        const result = http.get(`http://localhost:8095/api/v1/admin/sagas/${id}`, {
            headers: {Authorization: `Bearer ${__ENV.ADMIN_TOKEN}`},
            tags: {operation: 'saga_poll'},
            timeout: '5s',
            responseCallback: http.expectedStatuses(200, 404)
        });
        if (result.status === 200) {
            const state = result.json('state');
            if (state === 'COMPLETED') {
                saga.add(Date.now() - started);
                completed.add(1);
                return;
            }
            if (state === 'COMPENSATED' || state === 'MANUAL_REVIEW' || state === 'FAILED') {
                failed.add(1);
                return;
            }
        } else if (result.status === 404) pendingPolls.add(1); else pollErrors.add(1);
        sleep(1);
    }
    timeouts.add(1);
}
