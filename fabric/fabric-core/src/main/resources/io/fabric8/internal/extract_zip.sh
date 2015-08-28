function extract_zip {
  if ! which unzip &> /dev/null; then
        jar xf $1
  else
       unzip -o $1
  fi
}
