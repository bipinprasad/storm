#!/home/y/bin/python

# We are using /home/y/bin/python as this script needs to use python 2.7.
# When yinst runs, /bin/env python returns /usr/bin/python which
# can be python 2.6 for some deployed versions of ylinux.

import os
import json
import types

print """
# This configuration file is controlled by yinst set variables.
# This is for the resource-aware-scheduler

"""

def to_yaml(data, indent):
    dt = type(data)
    if dt is types.NoneType:
        ret = "null"
    elif dt is types.BooleanType:
        if data:
            ret = "true"
        else:
            ret = "false"
    elif dt in [types.IntType, types.LongType, types.FloatType]:
        ret = str(data)
    elif dt in [types.StringType, types.UnicodeType]:
        ret = "\"" + data.replace("\\","\\\\").replace("\"","\\\"") + "\""
    elif dt in [types.TupleType, types.ListType]:
        ret = "\n"
        for part in data:
            ret += "    " * indent + "- " + to_yaml(part, indent+1)+"\n"
    elif dt is types.DictType:
        ret = "\n"
        for k in sorted(data.iterkeys()):
            v = data[k]
            ret += "    " * indent + k + ": "+ to_yaml(v, indent+1)+"\n"
    else:
        raise "Don't know how to convert %s to YAML type is %s"%(data, dt)
    return ret

user_resource_pool_key = "resource.aware.scheduler.user.pools"

config = {k[8:].replace("_", ".") : v for k, v in os.environ.items() if k.startswith("ystorm__")}

resource_pool_json = config.get(user_resource_pool_key)
if resource_pool_json:
    resource_pool = ""
    try:
        resource_pool = {user_resource_pool_key: json.loads(resource_pool_json)}
    except:
        print "Error occurred in parsing config json!"
        raise

    yml = "";
    try:
        yml = to_yaml(resource_pool, 0)
    except:
        print "Error occurred in converting to YAML!"
        raise
    print (yml)
