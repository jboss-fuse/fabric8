function find_free_port() {
   START_PORT=$1
   END_PORT=$2
   for port in `eval echo {$START_PORT..$END_PORT}`;do
   	if [[ $OSTYPE == darwin* ]]; then
   		# macosx has a different syntax for netstat
   		netstat -atp tcp | tr -s ' ' ' '| cut -d ' ' -f 4 | grep ":$port" > /dev/null 2>&1 && continue || echo $port && break; 
   	else
		netstat -utan | tr -s ' ' ' '| cut -d ' ' -f 4 | grep ":$port" > /dev/null 2>&1 && continue || echo $port && break; 
   	fi
   done
}
