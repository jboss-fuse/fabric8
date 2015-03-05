function wait_for_port() {
    PORT=$1
    for i in {1..5};
        do
		   	if [[ $OSTYPE == darwin* ]]; then
		   		# macosx has a different syntax for netstat
		   		netstat -an -ptcp | grep LISTEN | tr -s ' ' ' '| cut -d ' ' -f 4 | grep ":$PORT" > /dev/null 2>&1 && break; 
		   	else
				netstat -lnt | tr -s ' ' ' '| cut -d ' ' -f 4 | grep ":$PORT" > /dev/null 2>&1 && break; 
		   	fi
			sleep 5;
        done
        return 0
}
