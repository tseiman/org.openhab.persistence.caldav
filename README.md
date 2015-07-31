## org.openhab.persistence.caldav
A CalDav persistence implementation for openHAB

## Introduction
CalDav persistence binding implements the same functionality as the openHAB GCal binding but connects 
instead to Google Calendar to any CalDAV enabled calendar server. At the moment it is used in a very similar 
way as the GCal binding.

CalDav persistence allows you to store events from openHAB in a CalDAV calendar and use those events as a presence simulation in
a home automation environment.

## Notice
- CalDav Persistence binding is beta and just tested on openHAB 1.8, on a few systems and needs more review
- CalDav Persistence binding is not fully integrated into openHAB's build system, for org.openhab.persistence.caldav the maven build is disfunctional. You are wellcome to contribute here ...

## Compatibility
- openHAB 1.8 (tested)
- java 1.7
- followigng calendar Servers have been tested:
  - [radicale](http://radicale.org) (tested, doesn't implement full requied function set, however it might work)
  - [davical](http://www.davical.org) (tested, fully compatible)

Please contact over openHAB Google group if you like to share your testing expirence.

## Preconditions
- You have installed a working calendar server such as
  - [davical](http://www.davical.org) (tested)
  - [bedework](https://www.apereo.org/projects/bedework) (un-tested)
  - or Apple's [Calendar Server](http://calendarserver.org) (un-tested)
You have 

