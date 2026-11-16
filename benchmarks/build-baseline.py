#!/usr/bin/env python3
"""Build a controlled sequential-publisher baseline with current domain/security code in isolation."""
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1];
work = Path(tempfile.mkdtemp(prefix='oms-baseline-'));
out = root / '.local/baseline-jars';
out.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root / 'pom.xml', work / 'pom.xml')
for section in ['libraries', 'services']:
    for source in (root / section).rglob('*'):
        relative = source.relative_to(root)
        if not source.is_file() or (
                'src' not in relative.parts and source.name != 'pom.xml') or 'target' in relative.parts: continue
        target = work / relative;
        target.parent.mkdir(parents=True, exist_ok=True);
        shutil.copyfile(source, target)
p = work / 'libraries/event-contracts/src/main/java/dev/oms/infra/Messaging.java';
text = p.read_text();
start = text.index('            // Queue a bounded batch');
end = text.index('\n          });', start)
text = text[:start] + '''            for (var row : rows) {
              try {
                kafka.send((String)row.get("topic"),row.get("aggregate_id").toString(),row.get("payload").toString()).get(3, TimeUnit.SECONDS);
                db.update("update outbox set published_at=clock_timestamp(),attempts=attempts+1 where event_id=?",row.get("event_id"));
              }catch(Exception failure){
                db.update("update outbox set attempts=attempts+1,next_attempt=clock_timestamp()+interval '10 seconds' where event_id=?",row.get("event_id"));break;
              }
            }''' + text[end:];
p.write_text(text)
profile = work / 'libraries/event-contracts/src/main/resources/application-tuned.yml';
profile.unlink(missing_ok=True)
projection = work / 'services/order-query-service/src/main/java/dev/oms/orderqueryservice/Queries.java'
query = projection.read_text();
begin = query.index('  // Rebuild from immutable buffered event history')
query = query[:begin] + '''  @PostMapping("/admin/projections/rebuild")
  @Transactional
  public Object rebuild() {
    db.execute("lock table order_read_models in exclusive mode");
    var events=db.queryForList("select event from projection_events order by order_id,version");
    db.update("delete from order_timeline");db.update("delete from order_read_models");
    for(var row:events)project(json.envelope(row.get("event").toString()));
    return Map.of("eventsReplayed",events.size());
  }
}
''';
projection.write_text(query)
env = os.environ.copy();
env['MAVEN_OPTS'] = '-Xmx384m -XX:ActiveProcessorCount=2'
subprocess.run(['mvn', '-f', str(work / 'pom.xml'), '-B', '-q', 'package', '-DskipTests'], env=env, check=True)
for service in (work / 'services').iterdir():
    if service.name == 'payment-provider': continue
    jar = service / 'target' / (service.name + '-1.0.0-SNAPSHOT.jar');
    shutil.copyfile(jar, out / (service.name + '.jar'))
(out / 'variant.json').write_text(json.dumps(
    {'variant': 'sequential outbox publisher and row-based projection rebuild; current correctness/domain code',
     'messaging_sha256': hashlib.sha256(text.encode()).hexdigest(),
     'projection_sha256': hashlib.sha256(query.encode()).hexdigest(), 'source_dir': str(work)}, indent=2));
print('Built isolated baseline:', out)
