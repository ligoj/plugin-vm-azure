define(function () {
	var current = {

		/**
		 * Render vCloud identifier.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:vm:vcloud:id');
		},

		/**
		 * Render VM vCloud.
		 */
		renderFeatures: function (subscription) {
			var result = '';
			if (subscription.parameters && subscription.parameters.console) {
				// Add Console
				result += '<button class="btn-link" data-toggle="popover" data-html="true" data-content="<img src=';
				result += '\'rest/service/vm/vcloud/' + subscription.id + '/console.png\'';
				result += '></img>"><span data-toggle="tooltip" title="' + current.$messages['service:vm:vcloud:console'];
				result += '" class="fa-stack"><i class="fa fa-square fa-stack-1x"></i><i class="fa fa-terminal fa-stack-1x fa-inverse"></i></span></button>';
			}
			return result;
		},

		configureSubscriptionParameters: function (configuration) {
			current.$super('registerXServiceSelect2')(configuration, 'service:vm:vcloud:id', 'service/vm/vcloud/');
		},

		/**
		 * Render vCloud details : id, name of VM, description, CPU, memory and vApp.
		 */
		renderDetailsKey: function (subscription) {
			var vm = subscription.data.vm;
			return current.$super('generateCarousel')(subscription, [
				[
					'service:vm:vcloud:id', current.renderKey(subscription)
				],
				[
					'name', vm.name
				],
				[
					'description', vm.description
				],
				[
					'service:vm:vcloud:resources', current.$super('icon')('sliders') + vm.memoryMB + ' Mo, ' + vm.numberOfCpus + ' CPU'
				],
				[
					'service:vm:vcloud:vapp', current.$super('icon')('server', 'service:vm:vcloud:vapp') + vm.containerName
				]
			], 1);
		}
	};
	return current;
});
