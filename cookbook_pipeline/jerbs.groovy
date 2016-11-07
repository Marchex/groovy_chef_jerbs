def version = '1.0'

def configure_environment() {
    env.PATH="/opt/chefdk/bin:/var/lib/jenkins/.chefdk/gem/ruby/2.3.0/bin:/opt/chefdk/embedded/bin:${env.PATH}:/opt/chefdk/gitbin"
    env.GEM_ROOT="/opt/chefdk/embedded/lib/ruby/gems/2.3.0"
    env.GEM_HOME="/var/lib/jenkins/.chefdk/gem/ruby/2.3.0"
    env.GEM_PATH="/var/lib/jenkins/.chefdk/gem/ruby/2.3.0:/opt/chefdk/embedded/lib/ruby/gems/2.3.0"
    env.KITCHEN_YAML=".kitchen.ec2.yml"
    env.KITCHEN_EC2_SSH_KEY_PATH="/var/lib/jenkins/.ssh/tools-team.pem"
}

def checkout_scm() {
    checkout scm
    sh '''
        gem list --local
        gem install foodcritic
        gem install bundler
        bundle install --path /var/lib/jenkins/.chefdk/gem                
    '''
}

def lint() {
    try {
        sh """
            rake lint
        """
    }
    catch (Exception err) {
        currentBuild.result = "UNSTABLE"
    }
    echo "RESULT: ${currentBuild.result}"
}

def chefspec() {
    sh """
        echo '--format RspecJunitFormatter' > .rspec
        echo '--out result.xml' >> .rspec
        rake chefspec
    """

    step([$class: 'JUnitResultArchiver', testResults: 'result.xml'])
}

def kitchen() {
    //wrap([$class: 'AnsiColorSimpleBuildWrapper', colorMapName: "xterm"]) {
        sh """
            rake kitchen
        """
    //}
}

def cleanup() {
   echo 'Cleanup'
//   mail body: "${env.BUILD_URL} build successful.\n" +
//               "Started by ${env.BUILD_CAUSE}",
//         from: 'tools-team@marchex.com',
//         replyTo: 'tools-team@marchex.com',
//         subject: "hostclass_publicftp ${env.JOB_NAME} (${env.BUILD_NUMBER}) build successful",
//         to: 'jcarter@marchex.com'
}
return this;
