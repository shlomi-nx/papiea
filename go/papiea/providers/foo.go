
func get_first_handler(c ctx, e entity) entity, err {
	//lazy_diff(e).get_first();
	first_diff = get_first_diff(e);
	if (first_diff == "name") {
		return rename_bar;
	}
	else (first_diff = "size") {
		return resize_bar;
	}
}

// we caluculate ops here
func update_dispatcher(c ctx, e entity) entity, err {
	return helper.get_first_handler(e)(e);
}

// ops list arrives from pipiea
func custom_dispatcher(c ctx, e entity, ops List) entity, err {
	//if (ops.find(rename_bar) < ops.find(resize_bar)) {
	//}
	return ops[0](e);
}

func rename_bar(c ctx, m const metadata, spec const spec, status status) status, err {
	//fs.rename(e.status.name, e.spec.name).wait()
	status.name = spec.name;
	return status, nil;
}

// provider is handling rbac
func resize_bar(c ctx, m const metadata, sp const spec, status status) status,err {
	err = nil
	if (c.user == "Aaron") {
		return status, "Aaron is not allowed to rename"
	}
	if (proper(size)) {
		status.size = e.spec.size;
	} else {
		err = "cant resize"
	}

	return status, err;
}


rbac_map = {"resize" : ["{mid}.size"],
	    "update" : ["{mid}.name", "{mid}.size"]}

Papiea.rbac(rbac_map);
Papiea.on("{mid}.size", "update;resize", resize_bar);


func create_bar(c ctx, m const metadata, sp const spec, status status) status, err {
	status = spec;
	status.size_on_disk = round_up(spec.size);
	return status, nil;
}

func scrub_file(c ctx, e const entity, args request) response, err {
	
}

Papiea.procedure("bar/{mid}/scrub", scrub_rbac, scrub_file);

// TODO: Generate swagger out of provider file


func runbook_operation(e ) {
	// papiea.entity will just be a funciton that invokes papiea's get entity rest api
	e1 = papiea.entity(e.host).wait();
	s = e1.status.size + 2
	e2 = papiea.spec(s).wait();
}



// TODO: Papiea needs to have a no-progress check, and will stop retrying after specified attempts

// TODO: When the error is returned, it will get stored in the task

// TODO: In entity-deleter, also delete entities that were failed to be born (meaning, create task failed)

// TODO: ctx (or context) will contain things like the acting user, his permissions etc.

// TODO: Dispatcher could choose the strategy:
//       - Serial/Parallel - could be provided through the yaml (instead of inheriting in a library)
//       - Stop on first failure/fail and continue to next update - 
//       - custom which will be custom logic 

// TODO: How to decouple provider from papiea such that if papiea dies and returns, it would know what is the status of the task
//       The problem right now is that papiea is updating the task based on the retun value from the provider

// TODO: How do these functions get transformed into endpoints?
//       1. Internal functions - promises to not change spec nor status (such as restart)

