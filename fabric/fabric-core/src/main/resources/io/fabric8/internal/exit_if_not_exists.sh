function exit_if_not_exists() {
 if [ ! -f $1 ]; then
          echo "Command Failed:Could not find file $1";
          exit -1;
 fi
 local zipFile="$1"
 local size="$(du $zipFile |  awk '{ print $1}')"
 if [ $size -lt 100 ]; then
          echo "Command Failed: Zip archive is empty. Check $1";
          exit -1;
 fi

}
