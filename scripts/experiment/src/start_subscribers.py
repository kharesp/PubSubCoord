import json,subprocess,conf,multiprocessing,metadata
from sys import argv

def launch_sub(host,topic_count_map,sample_count,run_id):
  command_string='cd %s && ansible-playbook playbooks/experiment/subscriber.yml --limit %s\
    --extra-vars="topic_count_map=%s sample_count=%d run_id=%s"'%\
    (metadata.ansible,host,str(topic_count_map).replace(" ",""),sample_count,run_id)
  subprocess.check_call(['bash','-c',command_string])

if __name__=="__main__":
  host_topic_map=json.loads(argv[1])
  sample_count=int(argv[2])
  run_id=argv[3]
  processes=[]
  for host,topic_count_map in host_topic_map.items():
    processes.append(multiprocessing.Process(target=launch_sub,args=(host,topic_count_map,sample_count,run_id)))

  for p in processes:
    p.start()
  for p in processes:
    p.join()
