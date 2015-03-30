#!/bin/bash

readonly NEXT_TAG=$1

lein release :patch
git checkout tags/${NEXT_TAG}
lein doc
git checkout gh-pages
mv -f doc/*.html .
git commit -am "updated docs"
git push --set-upstream origin gh-pages
git checkout master
