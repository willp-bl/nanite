Nanite - a friendly swarm of format-identifying robots
======================================================

[![Build Status](https://travis-ci.org/openplanets/nanite.png?branch=master)](https://travis-ci.org/openplanets/nanite)

<img src="https://github.com/openplanets/nanite/raw/master/docs/nanite_logo.png" alt="Nanite logo" width="200px" />

The Nanite project builds on DROID and Apache Tika to provide a rich format identification and characterization system. It aims to make it easier to run identification and characterisation at scale, and helps compare and combine the results of different tools.

* nanite-core contains the core identification code, a wrapped version of [DROID](https://github.com/digital-preservation/droid) that can parse InputStreams.
* nanite-hadoop allows nanite-core identifiers to be run on web archives via Map-Reduce on Apache Hadoop. It depends on the (W)ARC Record Readers from the WAP codebase. It can also use [Apache Tika](http://tika.apache.org/) and [libmagic](https://github.com/openplanets/libmagic-jna-wrapper) for identification.  Files can be characterized using Tika and output in a format suitable for importing into [C3PO](https://github.com/openplanets/c3po).

Nanite has been used at scale, see this [blog post](http://www.openplanetsfoundation.org/blogs/2014-05-28-weekend-nanite)

Acknowledgements
----------------

This work was partially supported by the [SCAPE project](http://scape-project.eu/). The SCAPE project is co-funded by the European Union under FP7 ICT-2009.4.1 (Grant Agreement number 270137)
