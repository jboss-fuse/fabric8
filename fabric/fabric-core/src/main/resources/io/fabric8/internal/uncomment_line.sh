function uncomment_line {
  echo "Uncommenting line starting with $1 in $2"
  sed "s#^\s*\#\s*\($1\)\s*=#\1=#g" $2 > $2.tmp
  rm $2
  mv $2.tmp $2
}
