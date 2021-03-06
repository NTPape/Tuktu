# Tuktu - Commodity Big Data Analytics
Tuktu is a big data analytics platform that focuses on ease of use. The idea of the platform is that its users can focus on (business) logic rather than dealing with technical implementation details. As such, the core of Tuktu offers a number of focal points.

- Easy setup and usage
- Social analytics out-of-the-box 
- Support for both synchronous and asynchronous processing
- Native support for batch jobs as well as everlasting streaming jobs
- Distributed computation
- Easy integration with big data/NoSQL tooling
- Easily extendable

The name comes from the Inuït word *tuktu*, which freely translates to the English word *reindeer* (also known as *[caribou](http://en.wikipedia.org/wiki/Caribou)*).

# Installing

Installing Tuktu can be done by either building from source as described below, or by downloading a fully packaged (including all the submodules mentioned here) release from the following URL:

[http://tuktu.et4it.nl/downloads/tuktu-0.1.zip](http://tuktu.et4it.nl/downloads/tuktu-0.1.zip) 

Note that Tuktu requires Java (JRE) to be present on the system it is run from. While Tuktu can be compiled against different java versions, this download was build against **java 1.8** and hence that version of java is required to run it.

Note that in order to run Tuktu, you need to navigate a terminal or command prompt to the root folder and then execute the command `bin\tuktu(.bat)`.

# Building from Source

To build Tuktu from source, clone the repository. Tuktu was built against Play! version 2.3.x so you need that version of Play.

**Important note:** If you want to build Tuktu as-is from cloning the git repository - including the NLP module - you will need to have the following NLP libraries installed to your local Maven or otherwise present on the classpath:

- [LIGA](https://github.com/ErikTromp/LIGA) - Language Detection library
- [OpenNLPPOSWrapper](https://github.com/ErikTromp/OpenNLPPOSWrapper) - Part-of-Speech Tagging wrapper around the OpenNLP library
- [RBEM](https://github.com/ErikTromp/RBEM) - Multi-lingual Sentiment Analysis library

To build Tuktu (for production), run:

`activator clean dist`

This will generate a compressed archive in the *target/universal/* folder of the root folder of the Tuktu project. This archive can be extracted and contains a startup script named *bin/tuktu(.bat)* that can be used to start Tuktu in production mode.

To run Tuktu on your local machine for testing and to be able to modify it using the [Scala IDE](http://scala-ide.org/), run:

`activator clean eclipse ~run`

You can now navigate to [http://localhost:9000](http://localhost:9000) to access Tuktu's server.

# Usage

Tuktu offers a web interface that allows to access its core functionalities like submitting jobs and monitoring running jobs. Navigate to [http://localhost:9000](http://localhost:9000) to access the web interface.

Tuktu jobs are essentially JSON configuration files that live in a pre-defined folder (the *configs* folder by default). Configuration files should adhere to standards. To learn how to create configuration files, the best way to start is by looking at some [examples](examples.md).

# Modeller

Instead of writing configuration files yourself, you can make use of a graphical modeller that saves a lot of work and makes configuration creation dead simple. The modeller is a separate project that can be found [here](https://github.com/NTPape/tuktumodeller).

The modeller can also be downloaded from the following URL [http://tuktu.et4it.nl/downloads/modeller-1.0.zip](http://tuktu.et4it.nl/downloads/modeller-1.0.zip). To run the modeller, you can do the same as for Tuktu, but when Tuktu is already running on port 9000, you might want to run the modeller on a different port as such: `bin\modeller(.bat) -Dhttp.port=9001`. The modeller is now available at [http://localhost:9001](http://localhost:9001).


# Examples

Examples of Tuktu configuration files can be found on the [examples](examples.md) page.

# Extending

Tuktu is set up in a modular way and is easily extended, provided some rules are adhered to. Example extensions can be found in the *modules* subfolder. Extending the Tuktu platform requires cloning or downloading the sources from this repository as a whole, or at the very least the *api* submodule.

- Every extension should be placed in the *modules* subfolder.
- An extension can be a Play! project on its own, if it is required to use Play! libraries or to even add to the routing of Tuktu. If an extension is a Play! project, it must be of version 2.3.x to prevent version conflicts.
- Tuktu has a submodule named *api*. This provides bare-bones classes and utilities used in Tuktu. An extension should depend on this submodule.
- Package names should always start with the prefix `tuktu.`. It is good practice to place collections of typical processors in a `tuktu.[modulename].processors` package and generators in a `tuktu.[modulename].generators` package.
- Dependencies and project configurations of the submodule should be defined at the root project level and only there, do not create submodule-specific configuration and project files in the submodule folder itself. 

# Core Concepts

Tuktu is built around the [Play!](http://playframework.com/) framework. It hence lives as a basic HTTP server but has other ways of invoking it besides HTTP-based traffic. Tuktu makes heavy use of the Play! [Iteratee](https://www.playframework.com/documentation/2.3.4/api/scala/index.html#play.api.libs.iteratee.package) library and hence also [Akka](http://akka.io/)

There are two basic types of actors in Tuktu.

- Generators
- Processors

**Generators** are actors that gather data from the external environment (outside of Tuktu), for example from the filesystem, a remote location or by simply 'creating' data (think of a periodic time-tick). As soon as a generator has a data item that is complete, it will stream it into a series of processors.

**Processors** are actors that manipulate data in one way or another. Processors can be chained together, executed in parallel with a merge-step or can copy data into multiple subsequent processors. This way, Tuktu creates a tree of processors that operate on a single data packet injected by a generator.

## Dispatcher

The core component of Tuktu is an actor named the *Dispatcher*. This actor can receive specific requests that make it set up generators and their accompanying processors. It does so based on a JSON configuration file.

Requests to the Dispatcher can either be synchronous or asynchronous. Synchronous requests return the data in a streaming way after it has undergone all transformations by the processors. Asynchronous requests do not return results but have potential side-effects instead. If possible, it is always advised to use asynchronous requests.

By default, a generator is a single (Akka) actor that has processors living with it. This means that a generator and the entire pipeline of processors it reaches, live on the same node of a cluster. The implication is that transactional flows are trivially easy to model in Tuktu but the downside is that this can put a computational burden on the actor. A generator by default is created on the Tuktu-node that the Dispatcher is invoked on, but can be explicitly executed on a specific node by specifying so in the configuration file.

This typical transactional flow can be broken by processors that need do so. There are numerous meta-processors in Tuktu that are specifically designed to introduce an explicit asynchronicity, execute remote computations or initiate distributed computations.

## Configuration Files

The way the Dispatcher sets up a flow of generators and processors is by using a JSON configuration file. This file describes what generators and processors to use, with their respective individual configuration, and how to chain them together. Additionally, the configuration can dictate on which node a generator should be created.

A typical configuration can look something like this. Note that generator-specific configuration has been omitted for compactness.

    {
		"generators": [
			{
				"name": "tuktu.social.generators.TwitterGenerator",
				"result": "data",
				"config": {
					...
	 			},
				"next": [
					"tokenizer", "debug"
				],
				"node": "192.168.2.1"
			}
		],
		"processors": [
			{
				"id": "debug",
				"name": "tuktu.processors.ConsoleWriterProcessor",
				"result": "",
				"config": {
					...
				},
				"next": [ ]
			},
			{
				"id": "tokenizer",
				"name": "tuktu.nlp.TokenizerProcessor",
				"result": "tokens",
				"config": {
					...
				},
				"next": [
					"li"
				]
			},
			{
				"id": "li",
				"name": "tuktu.nlp.LIGAProcessor",
				"result": "language",
				"config": {
					...
				},
				"next": [
					"csv"
				]
			},
			{
				"id": "csv",
				"name": "tuktu.processors.CSVWriterProcessor",
				"result": "",
				"config": {
					...
				},
				"next": []
			}
		]
	}

Let's examine this configuration file in a bit more detail. Notice that specifying a processing flow in Tuktu does not require you to program any code, as the logic designer, you just configure the platform.

1. The generator that will be used here is of type `tuktu.social.generators.TwitterGenerator`. This generator is a special generator that comes from the *social* submodule of Tuktu. It connects to the [Twitter Streaming API](https://dev.twitter.com/streaming/overview) with specific credentials and filters to gather tweets. When a single tweets is returned by Twitter, it is forwarded to the first processors in line.
2. The processors that the generator first sends data to are the *tokenizer* and the *debug* processors. Note that this directly implies that data is being copied to multiple processors.
3. The *debug* processor is of type `tuktu.processors.ConsoleWriterProcessor` and simply writes data to stdout when it obtains some. The *tokenizer* processor is of type `tuktu.nlp.TokenizerProcessor` and stems from the NLP submodule of Tuktu. This processor splits text (for example, the tweet body) into tokens (words, figures, characters, etc.).
4. After tokenization, the data is sent to a language identification processor of type `tuktu.nlp.LIGAProcessor`. This processor enriches the data by adding a language field to it.
5. Finally, data is sent to a processor of type `tuktu.processors.CSVWriterProcessor`, which streams the data into a CSV file.

Using this configuration file, data is obtained from Twitter, written out for debugging, tokenized and has language identifcation applied to it. The result is finally written to a CSV file. Note that in this case, because we make use of the generator `tuktu.social.generators.TwitterGenerator`, the processing never ends, this is a perpetual process and hence the CSV file is never closed. There are specific generators however that can end their data ingestion, in which case the entire data processing pipeline is shut down and closed accordingly. In this case, the CSV file would be closed nicely.

## Default Entry Points

The Tuktu platform ignites a data processing flow as soon as the Dispatcher gets a request to do so. Besides using the web interface to initiate jobs, Tuktu has other ways of invoking the Dispatcher.

The Play! nature of Tuktu made for usage of the Dispatcher using an HTTP request. By default, the URL `/dispatch/:configName` can be used to start data processing. The GET parameter `configName` is what the Dispatcher uses to find a JSON file stored on the same node Tuktu is running on, in a special configuration repository location, that will serve as the configuration file.

Alternatively, a configuration file can be given to Tuktu. This can be done by simply invoking the Dispatcher actor or by making a POST request to `/config`, where the body should be no different from a regular configuration file, except for that an additional field named *id* should present in the configuration file.

Lastly, since the Dispatcher is nothing more than an Akka actor, it can be invoked programmatically by sending it a `DispatchRequest` (defined in the Dispatcher itself).

# Standard Submodules

Tuktu comes with a number of submodules by default. Read more about them here.

- The [API](modules/api) submodule. This is actually part of Tuktu's core and used for extending the platform.
- The [CSV](modules/csv) submodule. In Big Data, CSV files are often used to export legacy data into NoSQL systems. This package helps with that.
- The [ML](modules/ml) submodule. This module contains some machine learning functionality.
- The [NLP](modules/nlp) submodule. This module contains some algorithms on Natural Language Processing.
- The [NoSQL](modules/nosql) submodule. This module contains standard methods to read or write from and to some popular NoSQL systems.
- The [Social](modules/social) submodule. This module contains generators for social media and some basic processors. 
- The [Web](modules/web) submodule. This module will deal with HTTP traffic and has functionality for performing wb analytics.