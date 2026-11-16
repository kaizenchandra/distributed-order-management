import http from 'k6/http';
import {Counter, Trend} from 'k6/metrics';

const queries = new Counter('oms_queries'), failures = new Counter('oms_query_failures'),
    latency = new Trend('oms_query_ms', true);
const ids = JSON.parse(__ENV.ORDER_IDS);
export const options = {
    scenarios: {
        reads: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 100),
            timeUnit: '1s',
            duration: __ENV.DURATION || '10s',
            preAllocatedVUs: Number(__ENV.VUS || 50),
            maxVUs: Number(__ENV.MAX_VUS || 100),
            gracefulStop: '10s'
        }
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {http_req_failed: ['rate==0'], dropped_iterations: ['count==0']}
};
export default function () {
    const id = ids[(__VU + __ITER) % ids.length];
    const response = http.get(`http://localhost:8096/api/v1/query/orders/${id}`, {
        headers: {Authorization: `Bearer ${__ENV.CUSTOMER_TOKEN}`},
        timeout: '5s'
    });
    queries.add(1);
    latency.add(response.timings.duration);
    if (response.status !== 200) failures.add(1);
}
